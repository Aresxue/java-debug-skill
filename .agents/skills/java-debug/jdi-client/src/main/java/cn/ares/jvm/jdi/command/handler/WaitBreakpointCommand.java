package cn.ares.jvm.jdi.command.handler;

import cn.ares.jvm.jdi.command.CommandArgs;
import cn.ares.jvm.jdi.command.ConnectedCommand;
import cn.ares.jvm.jdi.command.EventResponses;
import cn.ares.jvm.jdi.command.Responses;
import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;
import cn.ares.jvm.jdi.execution.DebugEvent;
import cn.ares.jvm.jdi.execution.ExecutionController;

import java.util.List;
import java.util.Map;

/**
 * wait-breakpoint: long-poll for the next hit on a thread no step command owns.
 */
public class WaitBreakpointCommand extends ConnectedCommand {

    public WaitBreakpointCommand(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    protected Map<String, Object> execute(ConnectionContext ctx, Map<String, Object> cmd,
                                          Map<String, Object> resp) {
        long timeoutMs = CommandArgs.intField(cmd, "timeout", 180, 1, 3600) * 1000L;

        ExecutionController controller = ctx.getExecutionController();
        DebugEvent event = controller.waitForBreakpoint(timeoutMs);
        if (null == event) {
            Responses.error(resp, "Timeout", "No breakpoint hit within " + (timeoutMs / 1000) + "s");
            EventResponses.putDiagnostics(ctx, resp);
            List<Map<String, Object>> frozenList = controller.frozenHandedOut();
            if (!frozenList.isEmpty()) {
                // A response can be lost without anyone noticing (curl killed by a timeout while
                // the server still writes into the dead socket), so list what is still frozen
                resp.put("frozen_threads", frozenList);
                resp.put("frozen_hint", "These threads were reported by an earlier wait/step and"
                        + " are still frozen. Inspect them with stack/locals, then resume each one");
            }
            return resp;
        }

        if (DebugEvent.SUPERSEDED == event) {
            return Responses.error(resp, "Superseded",
                    "A newer wait-breakpoint took over; only the latest wait receives hits");
        }

        if (event.isTerminal()) {
            return EventResponses.putTerminal(resp, event);
        }

        EventResponses.putEvent(ctx, resp, event);
        EventResponses.putDiagnostics(ctx, resp);
        return resp;
    }
}
