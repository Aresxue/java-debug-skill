package cn.ares.jvm.jdi.execution;

import cn.ares.jvm.jdi.session.JvmSession;
import cn.ares.jvm.jdi.session.ThreadRegistry;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.StepRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Execution control: the freeze/wait/step/resume state machine the EventLoop feeds. A step request
 * created here is consumed and deleted through the event loop, and thread freeze/unfreeze is
 * balanced across resume() and the event loop's per-eventSet resume, so all the state they share
 * stays under one lock in this class. The invariant every path below has to preserve: a frozen
 * application thread must always have someone responsible for releasing it.
 */
public class ExecutionController {

  /**
   * Cap on breakpoint scenes kept at once, counting both queued events and events already handed to
   * a caller but not resumed. It bounds what the client holds, not how many threads the target has
   * suspended: JDWP suspends a thread as it produces each event, and everything sitting in the
   * target's EventQueue is suspended but invisible here, so the two counts come apart under a
   * concurrent storm. What this cap does guarantee is that a wait-without-resume loop cannot keep
   * taking the target's thread pool one hit at a time: past it, hits are refused by publishHit
   * before anything is done for them and released by the event loop's own EventSet resume
   */
  private static final int MAX_FROZEN_THREADS = 64;

  private final JvmSession session;
  private final ThreadRegistry threadRegistry;

  /**
   * Guards pendingEventList, steppingThreadIdSet, terminalEvent and breakpointWaitSeq. Waiters scan
   * the list in place under this lock instead of polling events out of a queue: a
   * polled-but-unmatched event would sit in that waiter's hands, invisible to the waiter it
   * actually belongs to, until the wrong waiter gives up
   */
  private final ReentrantLock eventLock = new ReentrantLock();
  private final Condition eventChanged = eventLock.newCondition();
  private final List<DebugEvent> pendingEventList = new LinkedList<>();

  /**
   * Threads a step command currently owns, from before the thread is resumed until the command
   * returns. Their events belong to that step and are hidden from wait-breakpoint, which would
   * otherwise take the step's result; membership also rejects a second concurrent step
   */
  private final Set<Long> steppingThreadIdSet = new HashSet<>();

  /**
   * VM death / disconnect concerns every waiter, so it is kept as state rather than queued as an
   * event only one waiter could take
   */
  private DebugEvent terminalEvent;

  /**
   * Only the latest wait-breakpoint stays alive. A wait whose curl was killed keeps polling on the
   * server, and left alone it would take the next hit and write it to a dead connection
   */
  private long breakpointWaitSeq;

  /**
   * Threads whose step request is still outstanding. Removing the entry is the ownership token
   * between the event loop and a timed-out step command
   */
  private final Map<Long, StepRequest> stepRequestMap = new ConcurrentHashMap<>();

  /**
   * Threads frozen by us whose event has already been handed to a caller. A handed-out event can
   * still be lost: a curl killed by a timeout leaves the server writing into a dead socket without
   * any error. Listing these on wait timeout keeps such a thread from staying frozen with nobody
   * aware of it
   */
  private final Map<Long, ThreadReference> handedOutMap = new ConcurrentHashMap<>();

  private final AtomicInteger droppedEventCount = new AtomicInteger();

  public ExecutionController(JvmSession session, ThreadRegistry threadRegistry) {
    this.session = session;
    this.threadRegistry = threadRegistry;
  }

  /**
   * Wait for the next breakpoint hit (or an orphaned step result) on a thread no step command owns.
   * Returns null on timeout, SUPERSEDED when a newer wait-breakpoint took over.
   */
  public DebugEvent waitForBreakpoint(long timeoutMs) {
    eventLock.lock();
    try {
      long mySeq = ++breakpointWaitSeq;
      // Wake the wait this one supersedes so it returns now instead of at its timeout
      eventChanged.signalAll();
      return await(timeoutMs, this::isUnclaimed, () -> mySeq != breakpointWaitSeq);
    } finally {
      eventLock.unlock();
    }
  }

  /**
   * Wait for the next event of a thread claimed by step(): its step result, or a breakpoint hit
   * that interrupted the step. Events of other threads stay in place for their own waiters. Returns
   * null on timeout.
   */
  public DebugEvent waitForThread(long timeoutMs, long threadId) {
    eventLock.lock();
    try {
      return await(timeoutMs, event -> threadIdOf(event) == threadId, () -> false);
    } finally {
      eventLock.unlock();
    }
  }

  /**
   * End the step command's ownership taken by a successful step().
   */
  public void releaseStep(long threadId) {
    eventLock.lock();
    try {
      steppingThreadIdSet.remove(threadId);
      // This thread's late events are claimable by wait-breakpoint from now on
      eventChanged.signalAll();
    } finally {
      eventLock.unlock();
    }
  }

  /**
   * Claim a thread for a step command. Returns false when another step already owns it.
   */
  private boolean claimStep(long threadId) {
    eventLock.lock();
    try {
      return steppingThreadIdSet.add(threadId);
    } finally {
      eventLock.unlock();
    }
  }

  /**
   * Track a thread again after an eval. A breakpoint hit inside the eval's invocation is released
   * with resume, which drops the thread from handedOutMap, yet JDI re-suspends it once the
   * invocation returns: without this it would sit frozen and missing from frozen_threads.
   */
  public void trackFrozen(long threadId) {
    ThreadReference threadRef = threadRegistry.findThread(threadId);
    if (null == threadRef) {
      return;
    }
    try {
      if (threadRef.isSuspended()) {
        handedOutMap.put(threadId, threadRef);
      }
    } catch (ObjectCollectedException | VMDisconnectedException ignored) {
    }
  }

  /**
   * Frozen threads that were already reported to some caller and not resumed since.
   */
  public List<Map<String, Object>> frozenHandedOut() {
    List<Map<String, Object>> frozenList = new ArrayList<>();
    Iterator<ThreadReference> it = handedOutMap.values().iterator();
    while (it.hasNext()) {
      ThreadReference threadRef = it.next();
      try {
        if (!threadRef.isSuspended()) {
          it.remove();
          continue;
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("thread_id", threadRef.uniqueID());
        info.put("thread_name", threadRef.name());
        frozenList.add(info);
      } catch (ObjectCollectedException | VMDisconnectedException e) {
        it.remove();
      }
    }
    return frozenList;
  }

  /**
   * Caller holds eventLock.
   */
  private DebugEvent await(long timeoutMs, Predicate<DebugEvent> matcher, BooleanSupplier superseded) {
    long remainNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    try {
      while (true) {
        if (null != terminalEvent) {
          return terminalEvent;
        }
        if (superseded.getAsBoolean()) {
          return DebugEvent.SUPERSEDED;
        }
        Iterator<DebugEvent> it = pendingEventList.iterator();
        while (it.hasNext()) {
          DebugEvent event = it.next();
          if (matcher.test(event)) {
            it.remove();
            handedOutMap.put(threadIdOf(event), event.thread);
            return event;
          }
        }
        if (remainNanos <= 0) {
          return null;
        }
        remainNanos = eventChanged.awaitNanos(remainNanos);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private boolean isUnclaimed(DebugEvent event) {
    return !steppingThreadIdSet.contains(threadIdOf(event));
  }

  private long threadIdOf(DebugEvent event) {
    return null == event.thread ? -1 : event.thread.uniqueID();
  }

  /**
   * Drop queued events of a thread that is about to run: they describe a frozen position the thread
   * is leaving, and handing one out later would report a stale location and locals.
   */
  private void discardPending(long threadId) {
    eventLock.lock();
    try {
      pendingEventList.removeIf(event -> threadIdOf(event) == threadId);
    } finally {
      eventLock.unlock();
    }
  }

  /**
   * Threads we keep frozen: queued events plus events handed out and not resumed yet.
   */
  public int frozenCount() {
    eventLock.lock();
    try {
      return frozenThreadIdSet().size();
    } finally {
      eventLock.unlock();
    }
  }

  /**
   * Distinct threads, not events: a handed-out thread can still queue an event of its own. Caller
   * holds eventLock.
   */
  private Set<Long> frozenThreadIdSet() {
    Set<Long> threadIdSet = new HashSet<>(handedOutMap.keySet());
    for (DebugEvent event : pendingEventList) {
      threadIdSet.add(threadIdOf(event));
    }
    return threadIdSet;
  }

  /**
   * Number of events dropped because too many threads were frozen, reset on read.
   */
  public int consumeDroppedEventCount() {
    return droppedEventCount.getAndSet(0);
  }

  // ==================== Event Loop Seam ====================

  /**
   * Whoever removes the record owns this step. A step command that already timed out or was
   * interrupted by a breakpoint cancelled its request, so a late event must not freeze the thread
   * again: nobody will ever send a resume for it.
   */
  boolean ownStepEvent(StepEvent event) {
    StepRequest owned = stepRequestMap.remove(event.thread().uniqueID());
    deleteQuietly(event.request());
    return null != owned;
  }

  /**
   * True when one more hit cannot be kept. A thread that is already counted does not need a new
   * slot, so it is never over the cap; a step is exempt because it re-freezes a thread the caller
   * is driving. Caller holds eventLock.
   */
  boolean overFrozenCap(long threadId) {
    eventLock.lock();
    try {
      Set<Long> frozenIdSet = frozenThreadIdSet();
      return !frozenIdSet.contains(threadId) && frozenIdSet.size() >= MAX_FROZEN_THREADS;
    } finally {
      eventLock.unlock();
    }
  }

  /**
   * Count a hit refused because too many threads were frozen.
   */
  void recordDroppedEvent() {
    droppedEventCount.incrementAndGet();
  }

  /**
   * Hand an event to whoever is waiting. The caller has already established that the hit can be
   * kept: publishHit refuses an over-cap hit before freezing the thread for it, which leaves
   * exactly one place deciding, and one resume releasing the thread.
   */
  void publish(DebugEvent event) {
    eventLock.lock();
    try {
      pendingEventList.add(event);
      eventChanged.signalAll();
    } finally {
      eventLock.unlock();
    }
  }

  /**
   * VM death/disconnect must reach every waiter at once, otherwise each pending command sits until
   * its own timeout. The first terminal event wins.
   */
  void publishControl(DebugEvent event) {
    eventLock.lock();
    try {
      if (null == terminalEvent) {
        terminalEvent = event;
      }
      eventChanged.signalAll();
    } finally {
      eventLock.unlock();
    }
  }

  // ==================== Thread Commands ====================

  /**
   * Resume a thread by ID. Returns null when the thread is unknown, FALSE when it was not suspended
   * and TRUE when it was actually released. Not being suspended is normal rather than an error: one
   * resume clears the whole suspend count, so repeating a resume has nothing left to release.
   * Events still queued for the thread are discarded, since they describe where it was frozen.
   */
  public Boolean resume(long threadId) {
    ThreadReference threadRef = threadRegistry.findThread(threadId);
    if (null == threadRef) {
      return null;
    }
    discardPending(threadId);
    if (!threadRef.isSuspended()) {
      return Boolean.FALSE;
    }
    forceResume(threadRef);
    return Boolean.TRUE;
  }

  /**
   * Create and enable a step request for the given thread. Returns error message or null on
   * success. On success the thread is claimed for the caller, who must wait with waitForThread and
   * then call releaseStep. The step event will be delivered via the event loop.
   */
  public String step(long threadId, String type) {
    ThreadReference threadRef = threadRegistry.findThread(threadId);
    if (null == threadRef) {
      return "Thread not found: " + threadId;
    }
    if (!threadRef.isSuspended()) {
      return "Thread is not suspended: " + threadId;
    }

    int depth;
    switch (type) {
      case "over":
        depth = StepRequest.STEP_OVER;
        break;
      case "into":
        depth = StepRequest.STEP_INTO;
        break;
      case "out":
        depth = StepRequest.STEP_OUT;
        break;
      default:
        return "Unknown step type: " + type + ". Use: over/into/out";
    }

    // Claim before the thread runs: a fast step can complete before the caller starts
    // waiting, and an unclaimed result would go to a background wait-breakpoint instead.
    // Two steps on one thread would delete each other's request, so the second is refused
    if (!claimStep(threadId)) {
      return "Another step is already in progress on thread: " + threadId;
    }
    try {
      // Remove any existing step request for this thread
      clearExistingStepRequests(threadRef);

      StepRequest stepRequest = session.getEventRequestManager()
          .createStepRequest(threadRef, StepRequest.STEP_LINE, depth);
      stepRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
      // One-shot
      stepRequest.addCountFilter(1);
      stepRequest.enable();
      stepRequestMap.put(threadId, stepRequest);

      // Resume the thread to execute the step. Its queued hits go stale the moment it
      // moves, and the step waiter must not mistake one of them for the step result
      discardPending(threadId);
      forceResume(threadRef);
      return null;
    } catch (RuntimeException runtimeException) {
      cancelStep(threadId);
      releaseStep(threadId);
      throw runtimeException;
    }
  }

  /**
   * Cancel an outstanding step request, used when the step command gives up waiting. Returns true
   * when this call owned the request, i.e. the step event had not arrived yet.
   */
  public boolean cancelStep(long threadId) {
    StepRequest stepRequest = stepRequestMap.remove(threadId);
    if (null == stepRequest) {
      return false;
    }
    deleteQuietly(stepRequest);
    return true;
  }

  private void clearExistingStepRequests(ThreadReference threadRef) {
    stepRequestMap.remove(threadRef.uniqueID());
    List<StepRequest> existingList = session.getEventRequestManager().stepRequests();
    for (StepRequest stepRequest : existingList) {
      if (stepRequest.thread().equals(threadRef)) {
        deleteQuietly(stepRequest);
      }
    }
  }

  /**
   * Resume until the suspend count reaches zero. One resume() only undoes one suspend, and a thread
   * that carries an extra one would be reported as released while it stays frozen and the caller
   * waits for an event that can never arrive.
   */
  private void forceResume(ThreadReference threadRef) {
    if (null == threadRef) {
      return;
    }
    try {
      // Read the count once: looping on a live re-read could spin forever against a
      // thread someone else keeps suspending
      int count = threadRef.suspendCount();
      for (int i = 0; i < count; i++) {
        threadRef.resume();
      }
    } catch (ObjectCollectedException | VMDisconnectedException ignored) {
    }
    // A running thread no longer needs a fast lookup; the next event caches it again
    threadRegistry.evictThread(threadRef);
    handedOutMap.remove(threadRef.uniqueID());
  }

  private void deleteQuietly(EventRequest eventRequest) {
    try {
      session.getEventRequestManager().deleteEventRequest(eventRequest);
    } catch (Exception ignored) {
    }
  }
}
