package cn.ares.jvm.jdi.execution;

import com.sun.jdi.Location;
import com.sun.jdi.ThreadReference;

import java.util.Map;

/**
 * One event handed to a wait or step command: a breakpoint hit, a step result, or the end of the
 * session.
 */
public class DebugEvent {

  /**
   * Terminal event types. A normal target exit sends VM_DEATH before the connection closes, so that
   * is the one waiters usually see; a kill -9 or a dropped link only produces a disconnect
   */
  public static final String VM_DISCONNECT = "vm-disconnect";
  public static final String VM_DEATH = "vm-death";

  /**
   * Returned to a wait-breakpoint that a newer wait-breakpoint took over
   */
  public static final DebugEvent SUPERSEDED = new DebugEvent("superseded", null, null, null, null);

  final String type;
  final ThreadReference thread;
  final Location location;
  final Map<String, Object> collectedState;
  final String bpId;

  DebugEvent(String type, ThreadReference thread, Location location, Map<String, Object> collectedState, String bpId) {
    this.type = type;
    this.thread = thread;
    this.location = location;
    this.collectedState = collectedState;
    this.bpId = bpId;
  }

  public String getType() {
    return type;
  }

  public ThreadReference getThread() {
    return thread;
  }

  public Location getLocation() {
    return location;
  }

  public Map<String, Object> getCollectedState() {
    return collectedState;
  }

  public String getBpId() {
    return bpId;
  }

  /**
   * VM death (the target exited) or disconnect: either way the session is over, and a waiter must
   * report an error rather than an event
   */
  public boolean isTerminal() {
    return VM_DISCONNECT.equals(type) || VM_DEATH.equals(type);
  }
}
