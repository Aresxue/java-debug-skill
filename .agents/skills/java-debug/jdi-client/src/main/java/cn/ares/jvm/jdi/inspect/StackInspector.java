package cn.ares.jvm.jdi.inspect;

import cn.ares.jvm.jdi.session.ClassLoaderResolver;
import cn.ares.jvm.jdi.session.ThreadRegistry;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of a suspended thread: its stack and the local variables of a frame.
 */
public class StackInspector {

  private static final int MAX_FRAMES = 20;

  private final ThreadRegistry threadRegistry;

  private final ClassLoaderResolver loaderResolver;

  public StackInspector(ThreadRegistry threadRegistry, ClassLoaderResolver loaderResolver) {
    this.threadRegistry = threadRegistry;
    this.loaderResolver = loaderResolver;
  }

  /**
   * Collect full state (stack + locals) from a suspended thread.
   */
  public Map<String, Object> collect(ThreadReference thread) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("stack", collectStackFromThread(thread, MAX_FRAMES));
    state.put("locals", collectLocalsFromThread(thread, 0));
    return state;
  }

  /**
   * Collect stack trace by thread ID.
   */
  public List<Map<String, Object>> collectStack(long threadId, int maxFrames) {
    ThreadReference thread = threadRegistry.findThread(threadId);
    if (null == thread) {
      return null;
    }
    return collectStackFromThread(thread, maxFrames);
  }

  /**
   * Collect locals by thread ID and frame depth.
   */
  public Map<String, String> collectLocals(long threadId, int frameDepth) {
    ThreadReference thread = threadRegistry.findThread(threadId);
    if (null == thread) {
      return null;
    }
    return collectLocalsFromThread(thread, frameDepth);
  }

  public Map<String, Object> locationToMap(Location location) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("class", location.declaringType().name());
    map.put("method", location.method().name());
    map.put("line", location.lineNumber());
    try {
      map.put("source", location.sourceName());
    } catch (AbsentInformationException e) {
      map.put("source", "unknown");
    }
    // Tells hot-redeployed copies of the class apart, and is what eval's class_loader_id takes
    map.put("class_loader_id", loaderResolver.loaderIdOf(location.declaringType()));
    return map;
  }

  private List<Map<String, Object>> collectStackFromThread(ThreadReference thread, int maxFrames) {
    List<Map<String, Object>> stackList = new ArrayList<>();
    try {
      List<StackFrame> frameList = thread.frames(0, Math.min(maxFrames, thread.frameCount()));
      for (StackFrame frame : frameList) {
        stackList.add(locationToMap(frame.location()));
      }
    } catch (IncompatibleThreadStateException incompatibleThreadStateException) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("_error", "Thread not suspended");
      stackList.add(err);
    }
    return stackList;
  }

  private Map<String, String> collectLocalsFromThread(ThreadReference thread, int frameDepth) {
    Map<String, String> localMap = new LinkedHashMap<>();
    try {
      StackFrame frame = thread.frame(frameDepth);
      try {
        List<LocalVariable> varList = frame.visibleVariables();
        Map<LocalVariable, Value> valueMap = frame.getValues(varList);
        for (LocalVariable var : varList) {
          localMap.put(var.name(), ValueFormatter.format(valueMap.get(var), 2));
        }
      } catch (AbsentInformationException absentInformationException) {
        localMap.put("_error", "No debug info (compiled without -g)");
      }
    } catch (IncompatibleThreadStateException incompatibleThreadStateException) {
      localMap.put("_error", "Thread not suspended");
    } catch (IndexOutOfBoundsException e) {
      localMap.put("_error", "Invalid frame depth: " + frameDepth);
    }
    return localMap;
  }
}
