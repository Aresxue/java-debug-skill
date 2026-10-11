package cn.ares.jvm.jdi.inspect;

import cn.ares.jvm.jdi.session.JvmSession;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ThreadReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The threads command's view of every thread in the target.
 */
public class ThreadLister {

  private final JvmSession session;

  public ThreadLister(JvmSession session) {
    this.session = session;
  }

  /**
   * List all threads with ID, name, status. A thread that ends between allThreads() and the read of
   * its details is gone rather than broken, so it is left out: one dead thread must not cost the
   * caller the whole list. A disconnect still propagates as VMDisconnectedException, since an empty
   * list would read as a target that has no threads.
   */
  public List<Map<String, Object>> listThreads() {
    List<Map<String, Object>> resultList = new ArrayList<>();
    for (ThreadReference threadRef : session.allThreads()) {
      Map<String, Object> info = new LinkedHashMap<>();
      try {
        info.put("id", threadRef.uniqueID());
        info.put("name", threadRef.name());
        info.put("status", threadStatusName(threadRef.status()));
        info.put("suspended", threadRef.isSuspended());
      } catch (ObjectCollectedException e) {
        // Ended while being described: nothing left to report, and the list is still useful
        continue;
      }
      resultList.add(info);
    }
    return resultList;
  }

  private String threadStatusName(int status) {
    switch (status) {
      case ThreadReference.THREAD_STATUS_ZOMBIE:
        return "zombie";
      case ThreadReference.THREAD_STATUS_RUNNING:
        return "running";
      case ThreadReference.THREAD_STATUS_SLEEPING:
        return "sleeping";
      case ThreadReference.THREAD_STATUS_MONITOR:
        return "monitor";
      case ThreadReference.THREAD_STATUS_WAIT:
        return "wait";
      case ThreadReference.THREAD_STATUS_NOT_STARTED:
        return "not_started";
      default:
        return "unknown";
    }
  }
}
