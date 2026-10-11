package cn.ares.jvm.jdi.session;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.request.EventRequestManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;

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
