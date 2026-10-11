package cn.ares.jvm.jdi.session;

import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread lookup for one connection. Without the cache every locals/eval/stack/step/resume command
 * pays a full allThreads() round trip, which on a Tomcat app means pulling 200+ thread references.
 */
public class ThreadRegistry {

  private final JvmSession session;

  private final Map<Long, ThreadReference> threadCacheMap = new ConcurrentHashMap<>();

  public ThreadRegistry(JvmSession session) {
    this.session = session;
  }

  /**
   * Find a thread by its JDI unique ID. Returns null if absent; throws VMDisconnectedException when
   * the VM is gone, so callers report a disconnect instead of "thread not found". Cached references
   * are validated before reuse so a dead thread cannot be handed back.
   */
  public ThreadReference findThread(long threadId) {
    ThreadReference cached = threadCacheMap.get(threadId);
    if (null != cached && isLive(cached)) {
      return cached;
    }
    threadCacheMap.remove(threadId);
    for (ThreadReference threadRef : session.getVm().allThreads()) {
      if (threadRef.uniqueID() == threadId) {
        threadCacheMap.put(threadId, threadRef);
        return threadRef;
      }
    }
    return null;
  }

  /**
   * Remember a thread reference handed to us by an event, so the following commands on that thread
   * skip the allThreads() scan entirely.
   */
  public void cacheThread(ThreadReference threadRef) {
    if (null == threadRef) {
      return;
    }
    try {
      threadCacheMap.put(threadRef.uniqueID(), threadRef);
    } catch (VMDisconnectedException ignored) {
    }
  }

  /**
   * Forget a thread once it runs again, so the cache only holds threads we froze instead of every
   * thread that ever hit a breakpoint.
   */
  public void evictThread(ThreadReference threadRef) {
    if (null == threadRef) {
      return;
    }
    threadCacheMap.remove(threadRef.uniqueID(), threadRef);
  }

  /**
   * Drop every cached reference, done when the connection is retired.
   */
  public void clear() {
    threadCacheMap.clear();
  }

  private boolean isLive(ThreadReference threadRef) {
    try {
      return ThreadReference.THREAD_STATUS_ZOMBIE != threadRef.status();
    } catch (ObjectCollectedException | VMDisconnectedException e) {
      return false;
    }
  }
}
