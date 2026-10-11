package cn.ares.jvm.jdi.breakpoint;

import com.sun.jdi.ReferenceType;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One logical breakpoint. Mutable fields are guarded by BreakpointManager's lifecycleLock.
 */
class BreakpointSpec {

    final String className;
    final String methodName;
    final int lineNumber;

    /**
     * Watches for further copies of the class for as long as the breakpoint exists
     */
    ClassPrepareRequest classPrepare;

    /**
     * Requests over every copy armed so far
     */
    final List<BreakpointRequest> requestList = new ArrayList<>();

    /**
     * Copies already handled, whether they produced requests or not
     */
    final Set<ReferenceType> resolvedTypeSet = new HashSet<>();

    /**
     * Copies the breakpoint could not be armed on, each with class_loader_id and reason, oldest
     * first. Keyed by copy so an entry lives exactly as long as that copy stays loaded
     */
    final Map<ReferenceType, Map<String, Object>> failureMap = new LinkedHashMap<>();

    BreakpointSpec(String className, String methodName, int lineNumber) {
        this.className = className;
        this.methodName = methodName;
        this.lineNumber = lineNumber;
    }
}
