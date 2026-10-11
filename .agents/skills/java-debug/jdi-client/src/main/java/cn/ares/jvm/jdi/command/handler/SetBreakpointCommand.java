package cn.ares.jvm.jdi.command.handler;

import cn.ares.jvm.jdi.breakpoint.BreakpointManager;
import cn.ares.jvm.jdi.breakpoint.BreakpointResult;
import cn.ares.jvm.jdi.command.CommandArgs;
import cn.ares.jvm.jdi.command.ConnectedCommand;
import cn.ares.jvm.jdi.command.Responses;
import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;

import java.util.List;
import java.util.Map;

/**
 * set-breakpoint: by class and line, or by class and method.
 */
public class SetBreakpointCommand extends ConnectedCommand {

    public SetBreakpointCommand(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    protected Map<String, Object> execute(ConnectionContext ctx, Map<String, Object> cmd,
                                          Map<String, Object> resp) {
        String className = (String) cmd.get("class");
        String method = (String) cmd.get("method");
        Object lineObj = cmd.get("line");

        if (null == className) {
            return Responses.error(resp, "InvalidArgs", "Missing 'class'");
        }

        BreakpointManager breakpointManager = ctx.getBreakpointManager();
        BreakpointResult result;
        if (null != lineObj) {
            // Strict: 12.9 must not quietly become line 12
            int line = CommandArgs.intField(cmd, "line", -1, 1, Integer.MAX_VALUE);
            result = breakpointManager.setBreakpoint(className, line);
        } else if (null != method) {
            result = breakpointManager.setBreakpoint(className, method);
        } else {
            return Responses.error(resp, "InvalidArgs", "Must specify 'method' or 'line'");
        }

        String error = result.getError();
        if (null != error) {
            return Responses.error(resp, "BreakpointFailed", error);
        }
        resp.put("status", "ok");
        resp.put("bp_id", result.getBpId());
        resp.put("deferred", result.isDeferred());
        if (result.getInstalledCount() > 0) {
            resp.put("installed", result.getInstalledCount());
        }
        List<Map<String, Object>> candidateList = result.getCandidateList();
        if (null != candidateList && candidateList.size() > 1) {
            // Several classloaders or overloads matched and all of them were armed: the
            // caller has to know a hit may come from any of these locations
            resp.put("candidates", candidateList);
        }
        return resp;
    }
}
