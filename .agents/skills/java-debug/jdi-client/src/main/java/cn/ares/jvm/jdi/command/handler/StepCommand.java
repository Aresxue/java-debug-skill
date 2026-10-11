package cn.ares.jvm.jdi.command.handler;

import cn.ares.jvm.jdi.command.CommandArgs;
import cn.ares.jvm.jdi.command.ConnectedCommand;
import cn.ares.jvm.jdi.command.EventResponses;
import cn.ares.jvm.jdi.command.Responses;
import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;
import cn.ares.jvm.jdi.execution.DebugEvent;
import cn.ares.jvm.jdi.execution.ExecutionController;

import java.util.Map;

/**
 * step: run a frozen thread to the next line (over/into/out) and wait for where it stops.
 */
public class StepCommand extends ConnectedCommand {

    /**
     * Extra wait after a step timed out but its event had already been claimed by the event
     * loop: the event is being published right now and is better returned than orphaned
     */
    private static final long STEP_GRACE_MS = 10_000L;

    public StepCommand(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    protected Map<String, Object> execute(ConnectionContext ctx, Map<String, Object> cmd,
                                          Map<String, Object> resp) {
        long threadId = CommandArgs.threadId(cmd);
        String type = (String) cmd.get("type");
        if (threadId < 0) {
            return Responses.error(resp, "InvalidArgs", "Missing 'thread_id'");
        }
        if (null == type) {
            type = "over";
        }
        // Validate every argument before step() touches JDI state: failing afterwards would
        // leave a resumed thread with a live step request that no command is waiting on
        long timeoutMs = CommandArgs.intField(cmd, "timeout", 30, 1, 3600) * 1000L;

        ExecutionController controller = ctx.getExecutionController();
        String error = controller.step(threadId, type);
        if (null != error) {
            return Responses.error(resp, "StepFailed", error);
        }

        DebugEvent event;
        try {
            event = awaitStepEvent(controller, threadId, timeoutMs, resp);
        } finally {
            controller.releaseStep(threadId);
        }
        if (null == event) {
            return resp;
        }

        if (event.isTerminal()) {
            return EventResponses.putTerminal(resp, event);
        }
        return EventResponses.putEvent(ctx, resp, event);
    }

    /**
     * Wait for the step result of a claimed thread. Returns null after filling resp with the
     * timeout error. Restricted to this thread: a concurrent hit on another thread would
     * otherwise be returned as this step's result, with its location and locals.
     * A breakpoint hit on this thread before the step completes (stepping over a call that
     * hits one) ends the step there, as in an IDE.
     */
    private DebugEvent awaitStepEvent(ExecutionController controller, long threadId, long timeoutMs,
                                      Map<String, Object> resp) {
        DebugEvent event = controller.waitForThread(timeoutMs, threadId);
        if (null != event) {
            if ("breakpoint-hit".equals(event.getType())) {
                // Still armed, the request would freeze the thread again once the caller resumes
                // it from the breakpoint, with nobody waiting for that step
                controller.cancelStep(threadId);
            }
            return event;
        }
        if (controller.cancelStep(threadId)) {
            // A breakpoint hit published right at the deadline means the thread is not running
            event = controller.waitForThread(0, threadId);
            if (null != event) {
                return event;
            }
            Responses.error(resp, "Timeout", "Step did not complete within " + (timeoutMs / 1000) + "s"
                    + ". Step request cancelled, the thread was left running");
            return null;
        }
        // The event loop claimed the step right at the deadline and is publishing it now:
        // return it here rather than leave it for whoever waits next
        event = controller.waitForThread(STEP_GRACE_MS, threadId);
        if (null == event) {
            Responses.error(resp, "Timeout", "Step did not complete within " + (timeoutMs / 1000) + "s"
                    + ". The step event arrived meanwhile, the thread is frozen at the next line"
                    + " and still needs an explicit resume; wait-breakpoint will report it");
        }
        return event;
    }
}
