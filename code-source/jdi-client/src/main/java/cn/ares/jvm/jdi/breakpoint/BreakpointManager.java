package cn.ares.jvm.jdi.breakpoint;

import cn.ares.jvm.jdi.session.ClassLoaderResolver;
import cn.ares.jvm.jdi.session.JvmSession;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages breakpoint lifecycle: set, clear, deferred breakpoints.
 * All breakpoints use SUSPEND_EVENT_THREAD policy.
 * One logical breakpoint can map to several JDI requests: the same class name may be loaded by
 * several classloaders and a method name may have overloads, and installing only the first
 * candidate silently produces a breakpoint that never fires. Copies loaded after the breakpoint
 * was set (hot redeploy, a plugin classloader) are covered too: each breakpoint keeps its
 * ClassPrepareRequest until it is cleared.
 */
public class BreakpointManager {

    /**
     * Failed copies remembered per breakpoint, newest kept
     */
    private static final int MAX_FAILURES = 10;

    private final JvmSession session;

    private final ClassLoaderResolver loaderResolver;

    /**
     * bpId -> the logical breakpoint, whether installed, still deferred, or both
     */
    private final Map<String, BreakpointSpec> specMap = new ConcurrentHashMap<>();

    /**
     * Reverse index so matching an incoming event to its bpId is a lookup, not a scan
     */
    private final Map<BreakpointRequest, String> requestToBpIdMap = new ConcurrentHashMap<>();

    private final AtomicInteger nextId = new AtomicInteger(1);

    /**
     * Guards operations that span several maps. ConcurrentHashMap only makes single-map access
     * atomic, and clearBreakpoint (HTTP thread) racing handleClassPrepare (event thread) would
     * otherwise report a breakpoint as cleared while the event thread still installs it
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    public BreakpointManager(JvmSession session, ClassLoaderResolver loaderResolver) {
        this.session = session;
        this.loaderResolver = loaderResolver;
    }

    /**
     * Set breakpoint by class name + method name.
     */
    public BreakpointResult setBreakpoint(String className, String methodName) {
        BreakpointSpec spec = new BreakpointSpec(className, methodName, -1);
        return register(spec, "Method '" + methodName + "' not found (or has no executable location) in " + className);
    }

    /**
     * Set breakpoint by class name + line number.
     */
    public BreakpointResult setBreakpoint(String className, int lineNumber) {
        BreakpointSpec spec = new BreakpointSpec(className, null, lineNumber);
        return register(spec, "No executable code at line " + lineNumber + " in " + className);
    }

    /**
     * Clear (remove) a breakpoint by ID.
     */
    public boolean clearBreakpoint(String bpId) {
        lifecycleLock.lock();
        try {
            BreakpointSpec spec = specMap.remove(bpId);
            if (null == spec) {
                return false;
            }
            deleteQuietly(spec.classPrepare);
            for (BreakpointRequest request : spec.requestList) {
                requestToBpIdMap.remove(request);
                deleteQuietly(request);
            }
            return true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Get breakpoint ID from a BreakpointRequest (for event matching).
     */
    public String getBreakpointId(EventRequest request) {
        if (request instanceof BreakpointRequest) {
            return requestToBpIdMap.get(request);
        }
        return null;
    }

    /**
     * Loaded copies of a breakpoint's class that the breakpoint could not be armed on, one entry
     * per copy. Surfaced to the caller so a permanently pending breakpoint is not mistaken for
     * "the class has not been loaded yet", and so a redeployed copy that lost the line is
     * visible even while an older copy still carries the breakpoint.
     * Each copy keeps its own entry until it is unloaded: another copy arming fine says nothing
     * about this one, which may well be a parallel webapp still serving traffic.
     */
    public List<Map<String, Object>> deferredFailures() {
        List<Map<String, Object>> reportList = new ArrayList<>();
        lifecycleLock.lock();
        try {
            for (Entry<String, BreakpointSpec> entry : specMap.entrySet()) {
                BreakpointSpec spec = entry.getValue();
                if (spec.failureMap.isEmpty()) {
                    continue;
                }
                // Unloaded copies are dropped here rather than on ClassUnloadEvent, which carries
                // only the class name and cannot tell which copy went away
                spec.failureMap.keySet().retainAll(session.getVm().classesByName(spec.className));
                for (Map<String, Object> failure : spec.failureMap.values()) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("bp_id", entry.getKey());
                    info.put("class", spec.className);
                    if (null != spec.methodName) {
                        info.put("method", spec.methodName);
                    }
                    if (spec.lineNumber > 0) {
                        info.put("line", spec.lineNumber);
                    }
                    info.putAll(failure);
                    reportList.add(info);
                }
            }
        } finally {
            lifecycleLock.unlock();
        }
        return reportList;
    }

    /**
     * Handle ClassPrepareEvent: arm every breakpoint on this class on the newly loaded copy.
     */
    public void handleClassPrepare(ClassPrepareEvent event) {
        ReferenceType type = event.referenceType();
        String loadedClass = type.name();

        lifecycleLock.lock();
        try {
            for (Entry<String, BreakpointSpec> entry : specMap.entrySet()) {
                BreakpointSpec spec = entry.getValue();
                // add() is the de-dup: register's lookup may already have seen this copy, and
                // several breakpoints on one class each deliver their own event for it
                if (!loadedClass.equals(spec.className) || !spec.resolvedTypeSet.add(type)) {
                    continue;
                }
                installOnLoad(entry.getKey(), spec, type);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    // ==================== Internal ====================

    private BreakpointResult register(BreakpointSpec spec, String notFoundError) {
        String bpId = "bp-" + nextId.getAndIncrement();
        BreakpointResult result = new BreakpointResult();
        result.bpId = bpId;

        lifecycleLock.lock();
        try {
            // Armed before the lookup and kept for the breakpoint's whole lifetime: checking first
            // would let the class load in between unseen, and copies loaded later (hot redeploy, a
            // new classloader) must get the breakpoint too. An event for a copy loading right now
            // waits for this lock and is then de-duplicated through resolvedTypeSet
            spec.classPrepare = armClassPrepare(spec.className);
            List<Location> locationList = new ArrayList<>();
            List<ReferenceType> missList = new ArrayList<>();
            try {
                for (ReferenceType type : session.getVm().classesByName(spec.className)) {
                    spec.resolvedTypeSet.add(type);
                    List<Location> typeLocationList = resolveLocations(type, spec);
                    if (typeLocationList.isEmpty()) {
                        missList.add(type);
                    }
                    locationList.addAll(typeLocationList);
                }
                if (spec.resolvedTypeSet.isEmpty()) {
                    specMap.put(bpId, spec);
                    result.deferred = true;
                    return result;
                }
                if (locationList.isEmpty()) {
                    deleteQuietly(spec.classPrepare);
                    result.error = notFoundError;
                    return result;
                }
                spec.requestList.addAll(createRequests(bpId, locationList));
                // Armed on some copies only: the others usually are a different version of the
                // class, and a hit on them will never come
                for (ReferenceType type : missList) {
                    recordFailure(spec, type, describeFailure(spec));
                }
            } catch (RuntimeException e) {
                deleteQuietly(spec.classPrepare);
                throw e;
            }
            specMap.put(bpId, spec);
            result.installedCount = locationList.size();
            result.candidateList = describeLocations(locationList);
            return result;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private ClassPrepareRequest armClassPrepare(String className) {
        ClassPrepareRequest request = session.getEventRequestManager().createClassPrepareRequest();
        request.addClassFilter(className);
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        request.enable();
        return request;
    }

    /**
     * Arm the breakpoint on a copy loaded after it was set. Caller holds lifecycleLock.
     */
    private void installOnLoad(String bpId, BreakpointSpec spec, ReferenceType type) {
        List<Location> locationList = resolveLocations(type, spec);
        if (locationList.isEmpty()) {
            // Keep the record and remember why: dropping it would turn the "deferred:true" answered
            // earlier into a silent permanent no-op, and another copy may still match
            recordFailure(spec, type, describeFailure(spec));
            return;
        }
        try {
            spec.requestList.addAll(createRequests(bpId, locationList));
        } catch (RuntimeException e) {
            // createRequests already rolled back; record the failure instead of aborting the
            // remaining breakpoints of this class
            recordFailure(spec, type, "Install failed after class load: " + e);
        }
    }

    /**
     * Remember why a copy did not get the breakpoint. The loader id is taken now: once that copy
     * is unloaded it can no longer be asked.
     */
    private void recordFailure(BreakpointSpec spec, ReferenceType type, String reason) {
        Map<String, Object> failure = new LinkedHashMap<>();
        loaderResolver.putClassLoader(failure, type);
        failure.put("reason", reason);
        // A redeploy loop that keeps losing the line would otherwise grow every response
        if (spec.failureMap.size() >= MAX_FAILURES) {
            Iterator<ReferenceType> oldest = spec.failureMap.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        spec.failureMap.put(type, failure);
    }

    /**
     * Create one request per location, all or nothing: on failure the requests armed so far are
     * not reachable from any spec yet, so clear-breakpoint could never delete them and they would
     * keep freezing threads.
     */
    private List<BreakpointRequest> createRequests(String bpId, List<Location> locationList) {
        List<BreakpointRequest> requestList = new ArrayList<>();
        try {
            for (Location location : locationList) {
                BreakpointRequest request = session.getEventRequestManager().createBreakpointRequest(location);
                requestList.add(request);
                request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                request.enable();
                requestToBpIdMap.put(request, bpId);
            }
            return requestList;
        } catch (RuntimeException e) {
            for (BreakpointRequest request : requestList) {
                requestToBpIdMap.remove(request);
                deleteQuietly(request);
            }
            throw e;
        }
    }

    private List<Location> methodLocations(List<ReferenceType> typeList, String methodName) {
        List<Location> locationList = new ArrayList<>();
        for (ReferenceType type : typeList) {
            for (Method method : type.methodsByName(methodName)) {
                Location location = method.location();
                if (null != location) {
                    locationList.add(location);
                }
            }
        }
        return locationList;
    }

    private List<Location> resolveLocations(ReferenceType type, BreakpointSpec spec) {
        if (spec.lineNumber > 0) {
            return lineLocations(type, spec.lineNumber);
        }
        if (null != spec.methodName) {
            return methodLocations(Collections.singletonList(type), spec.methodName);
        }
        return Collections.emptyList();
    }

    private String describeFailure(BreakpointSpec spec) {
        if (spec.lineNumber > 0) {
            return "Class loaded but no executable code at line " + spec.lineNumber
                    + " (compiled without -g, or the line holds no bytecode)";
        }
        return "Class loaded but method '" + spec.methodName
                + "' was not found (or has no executable location)";
    }

    /**
     * One entry per armed location. The loader is part of it because copies of a class from
     * different classloaders otherwise print identically.
     */
    private List<Map<String, Object>> describeLocations(List<Location> locationList) {
        List<Map<String, Object>> candidateList = new ArrayList<>();
        for (Location location : locationList) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("location", location.declaringType().name() + "." + location.method().name()
                    + location.method().signature() + ":" + location.lineNumber());
            loaderResolver.putClassLoader(info, location.declaringType());
            candidateList.add(info);
        }
        return candidateList;
    }

    /**
     * Every executable location of the line, not the first one: a single line can hold several
     * methods, a lambda or several statements, and arming only the first leaves the breakpoint
     * silent wherever the others are reached from. The response lists a location each, so a line
     * that arms more than one is visible as more than one entry and an installed count above one
     */
    private List<Location> lineLocations(ReferenceType type, int lineNumber) {
        try {
            return type.locationsOfLine(lineNumber);
        } catch (AbsentInformationException e) {
            // No debug info
            return Collections.emptyList();
        }
    }

    private void deleteQuietly(EventRequest request) {
        try {
            session.getEventRequestManager().deleteEventRequest(request);
        } catch (Exception ignored) {
        }
    }
}
