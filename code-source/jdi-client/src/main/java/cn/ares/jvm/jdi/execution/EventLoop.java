package cn.ares.jvm.jdi.execution;

import cn.ares.jvm.jdi.breakpoint.BreakpointManager;
import cn.ares.jvm.jdi.inspect.StackInspector;
import cn.ares.jvm.jdi.session.JvmSession;
import cn.ares.jvm.jdi.session.ThreadRegistry;
import com.sun.jdi.Location;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JDI event loop of one connection: takes every EventSet off the target's queue, turns
 * breakpoint and step events into hits for the ExecutionController, and always resumes the EventSet
 * afterwards.
 */
public class EventLoop implements Runnable {

  /**
   * How long stop() waits for the loop to exit. An interrupt ends remove() at once, so this only
   * covers a round still dispatching
   */
  private static final long STOP_JOIN_MS = 3_000L;

  private final ThreadRegistry threadRegistry;
  private final BreakpointManager breakpointManager;
  private final StackInspector inspector;
  private final ExecutionController controller;

  private volatile boolean running = true;
  private Thread thread;

  /**
   * Bound at construction rather than read from the session each round. Each connection has its own
   * session, so this is the queue of this loop's VM for its whole life
   */
  private final EventQueue eventQueue;

  public EventLoop(JvmSession session, ThreadRegistry threadRegistry, BreakpointManager breakpointManager, StackInspector inspector, ExecutionController controller) {
    this.threadRegistry = threadRegistry;
    this.breakpointManager = breakpointManager;
    this.inspector = inspector;
    this.controller = controller;
    this.eventQueue = session.getEventQueue();
  }

  public void start() {
    thread = new Thread(this, "Jdi-Event-Handler");
    thread.setDaemon(true);
    thread.start();
  }

  /**
   * Stop the event loop and wait for it to exit, so a reconnect never overlaps two loops. Returns
   * whether the loop has exited.
   */
  public boolean stop() {
    running = false;
    // Wake every wait/step of this connection now. The loop publishes this itself when it
    // sees the disconnect, but the interrupt below can beat it, and then a step would sit
    // until its own timeout, holding a permit and a thread id that a new VM may reuse
    controller.publishControl(new DebugEvent(DebugEvent.VM_DISCONNECT, null, null, null, null));
    Thread loop = thread;
    if (null == loop) {
      return true;
    }
    loop.interrupt();
    if (Thread.currentThread() == loop) {
      return true;
    }
    try {
      loop.join(STOP_JOIN_MS);
    } catch (InterruptedException interruptedException) {
      Thread.currentThread().interrupt();
    }
    return !loop.isAlive();
  }

  @Override
  public void run() {
    while (running) {
      try {
        // 1s timeout
        EventSet eventSet = eventQueue.remove(1000);
        if (null == eventSet) {
          continue;
        }
        try {
          dispatch(eventSet);
        } finally {
          // Must run even when a handler throws. Under SUSPEND_EVENT_THREAD, skipping
          // this resume leaves the event's thread frozen forever with nobody
          // responsible for it — including innocent classloading threads
          resumeQuietly(eventSet);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (VMDisconnectedException e) {
        controller.publishControl(new DebugEvent(DebugEvent.VM_DISCONNECT, null, null, null, null));
        break;
      } catch (Throwable t) {
        // Throwable, not Exception: an Error thrown by a handler would otherwise kill
        // the event loop and leave every frozen thread stranded
        System.err.println("Event loop error: " + t);
      }
    }
  }

  private void dispatch(EventSet eventSet) {
    // JDWP groups the breakpoint and step events of one thread at one location into a single
    // EventSet, in no specified order. Folding them into one hit per thread keeps a step from
    // returning the breakpoint half while the other half lingers as a duplicate
    Map<Long, ThreadHit> hitMap = new LinkedHashMap<>();
    for (Event event : eventSet) {
      if (event instanceof BreakpointEvent) {
        ThreadHit hit = hitOf(hitMap, (BreakpointEvent) event);
        if (null == hit.bpId) {
          hit.bpId = breakpointManager.getBreakpointId(event.request());
        }
      } else if (event instanceof StepEvent) {
        if (controller.ownStepEvent((StepEvent) event)) {
          hitOf(hitMap, (StepEvent) event).stepped = true;
        }
      } else if (event instanceof ClassPrepareEvent) {
        breakpointManager.handleClassPrepare((ClassPrepareEvent) event);
      } else if (event instanceof VMDisconnectEvent) {
        controller.publishControl(new DebugEvent(DebugEvent.VM_DISCONNECT, null, null, null, null));
        running = false;
      } else if (event instanceof VMDeathEvent) {
        controller.publishControl(new DebugEvent(DebugEvent.VM_DEATH, null, null, null, null));
        running = false;
      }
    }
    for (ThreadHit hit : hitMap.values()) {
      publishHit(hit);
    }
  }

  private ThreadHit hitOf(Map<Long, ThreadHit> hitMap, LocatableEvent event) {
    ThreadReference threadRef = event.thread();
    ThreadHit hit = hitMap.get(threadRef.uniqueID());
    if (null == hit) {
      hit = new ThreadHit(threadRef, event.location());
      hitMap.put(threadRef.uniqueID(), hit);
    }
    return hit;
  }

  private void publishHit(ThreadHit hit) {
    ThreadReference threadRef = hit.thread;
    // A hit that cannot be kept is refused before anything is done for it: an explicit
    // suspend plus a state read would otherwise be paid for a hit that is thrown away, and
    // the thread would sit frozen until the event loop resumes its EventSet. Kept here so
    // that resume is the single round trip that releases it
    if (!hit.stepped && controller.overFrozenCap(threadRef.uniqueID())) {
      controller.recordDroppedEvent();
      return;
    }

    // Explicitly suspend the thread to guarantee it stays frozen.
    // This increments suspend count so even if EventSet is GC'd or resumed,
    // the thread remains suspended until our explicit resume command.
    threadRef.suspend();
    threadRegistry.cacheThread(threadRef);

    Map<String, Object> state = collectStateQuietly(threadRef);
    // A step landing on a breakpoint line is still the step's result; bpId tells which one
    String type = hit.stepped ? "step-complete" : "breakpoint-hit";
    controller.publish(new DebugEvent(type, threadRef, hit.location, state, hit.bpId));
  }

  private Map<String, Object> collectStateQuietly(ThreadReference threadRef) {
    try {
      return inspector.collect(threadRef);
    } catch (Exception e) {
      Map<String, Object> state = new LinkedHashMap<>();
      state.put("_collect_error", e.getMessage());
      return state;
    }
  }

  private void resumeQuietly(EventSet eventSet) {
    try {
      eventSet.resume();
    } catch (VMDisconnectedException ignored) {
    }
  }

  /**
   * The events one EventSet carries for one thread, folded into a single hit.
   */
  private static class ThreadHit {

    final ThreadReference thread;
    final Location location;
    String bpId;
    boolean stepped;

    ThreadHit(ThreadReference thread, Location location) {
      this.thread = thread;
      this.location = location;
    }
  }
}
