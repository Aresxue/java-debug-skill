package cn.ares.jvm.jdi.session;

import com.sun.jdi.connect.spi.ClosedConnectionException;
import com.sun.jdi.connect.spi.Connection;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

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
