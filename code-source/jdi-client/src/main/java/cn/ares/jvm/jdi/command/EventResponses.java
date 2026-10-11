package cn.ares.jvm.jdi.command;

import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.execution.DebugEvent;
import cn.ares.jvm.jdi.execution.ExecutionController;
import com.sun.jdi.Location;
import com.sun.jdi.ThreadReference;

import java.util.List;
import java.util.Map;

/**
 * The response parts wait-breakpoint and step share: a delivered event, the end of the
 * session, and the diagnostics a waiter cannot otherwise discover.
 */
public final class EventResponses {

    private EventResponses() {
    }

    public static Map<String, Object> putEvent(ConnectionContext ctx, Map<String, Object> resp, DebugEvent event) {
        resp.put("status", "ok");
        resp.put("event", event.getType());
        ThreadReference thread = event.getThread();
        if (null != thread) {
            resp.put("thread_id", thread.uniqueID());
            resp.put("thread_name", thread.name());
        }
        Location location = event.getLocation();
        if (null != location) {
            resp.put("location", ctx.getStackInspector().locationToMap(location));
        }
        String bpId = event.getBpId();
        if (null != bpId) {
            resp.put("bp_id", bpId);
        }
        Map<String, Object> collectedState = event.getCollectedState();
        if (null != collectedState) {
            resp.putAll(collectedState);
        }
        return resp;
    }

    /**
     * Death and disconnect share one error code: the recovery is the same (connect once the
     * target is back), only the message tells them apart.
     */
    public static Map<String, Object> putTerminal(Map<String, Object> resp, DebugEvent event) {
        return Responses.error(resp, "VmDisconnected", DebugEvent.VM_DEATH.equals(event.getType())
                ? "Remote JVM exited" : "Remote JVM disconnected");
    }

    /**
     * Attach information the caller cannot otherwise discover: breakpoints that failed to install
     * after their class loaded, hits that were dropped because the scene cap was reached, and
     * how many scenes are kept right now so a growing resume debt is visible early.
     */
    public static void putDiagnostics(ConnectionContext ctx, Map<String, Object> resp) {
        List<Map<String, Object>> failureList = ctx.getBreakpointManager().deferredFailures();
        if (!failureList.isEmpty()) {
            resp.put("deferred_failures", failureList);
        }
        ExecutionController controller = ctx.getExecutionController();
        int frozenCount = controller.frozenCount();
        if (frozenCount > 0) {
            resp.put("frozen_count", frozenCount);
        }
        int dropped = controller.consumeDroppedEventCount();
        if (dropped > 0) {
            resp.put("dropped_events", dropped);
            resp.put("dropped_hint", "The 64-scene cap was reached, so these hits were released"
                    + " without collecting their state. Resume threads you are done with, narrow"
                    + " the breakpoint, or consume events faster");
        }
    }
}
