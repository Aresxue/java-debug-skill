package cn.ares.jvm.jdi.command.handler;

import cn.ares.jvm.jdi.command.ConnectedCommand;
import cn.ares.jvm.jdi.command.Responses;
import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;

import java.util.Map;

/**
 * clear-breakpoint: remove a breakpoint by bp_id.
 */
public class ClearBreakpointCommand extends ConnectedCommand {

    public ClearBreakpointCommand(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    protected Map<String, Object> execute(ConnectionContext ctx, Map<String, Object> cmd,
                                          Map<String, Object> resp) {
        String bpId = (String) cmd.get("bp_id");
        if (null == bpId) {
            return Responses.error(resp, "InvalidArgs", "Missing 'bp_id'");
        }
        boolean cleared = ctx.getBreakpointManager().clearBreakpoint(bpId);
        if (cleared) {
            resp.put("status", "ok");
            return resp;
        }
        return Responses.error(resp, "InvalidBreakpoint", "Breakpoint not found: " + bpId);
    }
}
