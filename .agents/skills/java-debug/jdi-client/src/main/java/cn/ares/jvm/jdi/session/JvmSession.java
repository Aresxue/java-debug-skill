package cn.ares.jvm.jdi.session;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.spi.ClosedConnectionException;
import com.sun.jdi.connect.spi.Connection;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.request.EventRequestManager;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Attaches to a remote JVM over JDWP and exposes its handles. Single source of truth for the VM
 * handle.
 */
public class JvmSession {

  /**
   * Written by connect/disconnect and read by every HTTP thread
   */
  private volatile VirtualMachine vm;

  /**
   * Liveness source for isConnected. JDI's reader thread closes the Connection the moment it reads
   * EOF or an I/O error, while the VirtualMachine itself never notices: validateVM() is a no-op and
   * name() returns a cached value, so probing the VM reports a dead target as alive
   */
  private volatile JdwpConnection connection;

  // ==================== Connection ====================

  /**
   * Attach over our own socket instead of the SocketAttach connector, so both the connect and the
   * handshake are time-bounded (see JdwpConnection.ATTACH_TIMEOUT_MS).
   */
  public void connect(String host, int port) throws IOException {
    Socket socket = new Socket();
    try {
      connectSocket(socket, host, port);
      JdwpConnection newConnection = new JdwpConnection(socket);
      newConnection.handshake();
      VirtualMachine newVm = Bootstrap.virtualMachineManager().createVirtualMachine(newConnection);
      this.connection = newConnection;
      this.vm = newVm;
    } catch (IOException | RuntimeException exception) {
      // Nobody owns the socket until createVirtualMachine returns, and on success it must
      // stay open, so it is closed here rather than by try-with-resources
      closeQuietly(socket);
      throw exception;
    }
  }

  private void connectSocket(Socket socket, String host, int port) throws IOException {
    try {
      socket.connect(new InetSocketAddress(host, port), JdwpConnection.ATTACH_TIMEOUT_MS);
    } catch (SocketTimeoutException e) {
      throw new IOException("JDWP attach timed out after " + JdwpConnection.ATTACH_TIMEOUT_MS / 1000
          + "s connecting to " + host + ":" + port, e);
    }
  }

  private void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }

  /**
   * Dispose the VM. The connection's thread cache is cleared by its owner beforehand, see
   * ConnectionManager.retire.
   */
  public void disconnect() {
    if (null != vm) {
      try {
        vm.dispose();
      } catch (VMDisconnectedException ignored) {
      }
      vm = null;
    }
  }

  /**
   * A half-open TCP connection (peer vanished without FIN/RST) still reads as connected until the
   * next JDWP command fails; that command then reports VmDisconnected.
   */
  public boolean isConnected() {
    if (null == vm) {
      return false;
    }
    JdwpConnection current = connection;
    return null != current && current.isOpen();
  }

  public String getVmName() {
    VirtualMachine current = vm;
    return null != current ? current.name() : null;
  }

  public String getVmVersion() {
    VirtualMachine current = vm;
    return null != current ? current.version() : null;
  }

  /**
   * Whether the debuggee is a JDK 9 or later VM, the versions whose agent leaks the declaring class
   * of a static invoke. version() is answered from JDI's own cache, not the target
   */
  public boolean isJdk9OrLater() {
    VirtualMachine current = vm;
    if (null == current) {
      return false;
    }
    try {
      return !current.version().startsWith("1.");
    } catch (RuntimeException e) {
      // A dying session: the ordinary route reports what is wrong with it
      return false;
    }
  }

  // ==================== VM Handles ====================

  /**
   * The VM, or VMDisconnectedException when there is none. A command that passed the connected
   * check can still race a disconnect clearing the field; reading it once into a local and failing
   * as a disconnect keeps that from surfacing as an NPE / InternalError.
   */
  public VirtualMachine getVm() {
    VirtualMachine current = vm;
    if (null == current) {
      throw new VMDisconnectedException("Not connected");
    }
    return current;
  }

  public EventRequestManager getEventRequestManager() {
    return getVm().eventRequestManager();
  }

  public EventQueue getEventQueue() {
    return getVm().eventQueue();
  }

  public List<ThreadReference> allThreads() {
    return getVm().allThreads();
  }
}

/**
 * JDWP over a plain socket through the public Connection SPI, the equivalent of JDI's own
 * SocketConnection. It exists only so the handshake can be read with a timeout.
 */
final class JdwpConnection extends Connection {

  /**
   * Bound for the TCP connect and, separately, for the JDWP handshake. The stock SocketAttach
   * connector cannot bound the handshake: its timeout argument only reaches Socket.connect and the
   * handshake reply is read with no timeout at all, so a peer that accepts the connection but never
   * answers (a hung or stopped JVM, a wrong port, a tunnel whose far end is stuck) would block
   * connect forever while it holds the client's lifecycle lock
   */
  static final int ATTACH_TIMEOUT_MS = 10_000;

  private static final byte[] HANDSHAKE = "JDWP-Handshake".getBytes(StandardCharsets.US_ASCII);

  /**
   * Every JDWP packet starts with an 11-byte header: length, id, flags, command or error code
   */
  private static final int HEADER_SIZE = 11;

  private final Socket socket;
  private final DataInputStream input;
  private final OutputStream output;
  private final ReentrantLock receiveLock = new ReentrantLock();
  private final ReentrantLock sendLock = new ReentrantLock();
  private final AtomicBoolean closed = new AtomicBoolean();

  JdwpConnection(Socket socket) throws IOException {
    this.socket = socket;
    this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    this.output = socket.getOutputStream();
  }

  void handshake() throws IOException {
    socket.setSoTimeout(ATTACH_TIMEOUT_MS);
    output.write(HANDSHAKE);
    byte[] reply = new byte[HANDSHAKE.length];
    try {
      input.readFully(reply);
    } catch (SocketTimeoutException e) {
      throw new IOException("JDWP handshake timed out after " + ATTACH_TIMEOUT_MS / 1000
          + "s: the port accepted the connection but no JDWP agent answered", e);
    } catch (EOFException e) {
      throw new IOException("JDWP handshake failed: connection closed by the target", e);
    }
    if (!Arrays.equals(HANDSHAKE, reply)) {
      throw new IOException(
          "JDWP handshake failed: unrecognized reply, the port is not a JDWP agent");
    }
    // From here on a read waits for as long as the VM stays silent: an idle debuggee is normal
    socket.setSoTimeout(0);
  }

  @Override
  public byte[] readPacket() throws IOException {
    requireOpen();
    receiveLock.lock();
    try {
      int first = input.read();
      if (first < 0) {
        // End of stream: an empty packet is how the Connection SPI reports it
        return new byte[0];
      }
      byte[] lengthBytes = new byte[4];
      lengthBytes[0] = (byte) first;
      input.readFully(lengthBytes, 1, 3);
      int length = ByteBuffer.wrap(lengthBytes).getInt();
      if (length < HEADER_SIZE) {
        throw new IOException("Protocol error - invalid packet length: " + length);
      }
      byte[] packet = Arrays.copyOf(lengthBytes, length);
      input.readFully(packet, 4, length - 4);
      return packet;
    } catch (IOException e) {
      throw closedOr(e);
    } finally {
      receiveLock.unlock();
    }
  }

  @Override
  public void writePacket(byte[] packet) throws IOException {
    requireOpen();
    if (packet.length < HEADER_SIZE) {
      throw new IllegalArgumentException("Packet is insufficient size");
    }
    int length = ByteBuffer.wrap(packet, 0, 4).getInt();
    if (length < HEADER_SIZE || length > packet.length) {
      throw new IllegalArgumentException("Invalid packet length: " + length);
    }
    sendLock.lock();
    try {
      output.write(packet, 0, length);
    } catch (IOException e) {
      throw closedOr(e);
    } finally {
      sendLock.unlock();
    }
  }

  @Override
  public void close() throws IOException {
    if (closed.compareAndSet(false, true)) {
      socket.close();
    }
  }

  @Override
  public boolean isOpen() {
    return !closed.get();
  }

  private void requireOpen() throws ClosedConnectionException {
    if (closed.get()) {
      throw new ClosedConnectionException("Connection is closed");
    }
  }

  /**
   * A read or write failing because close() shut the socket is a close, not an I/O error.
   */
  private IOException closedOr(IOException ioException) {
    return closed.get() ? new ClosedConnectionException("Connection is closed") : ioException;
  }
}
