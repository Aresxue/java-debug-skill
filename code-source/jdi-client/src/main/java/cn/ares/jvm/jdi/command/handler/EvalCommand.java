package cn.ares.jvm.jdi.command.handler;

import cn.ares.jvm.jdi.command.CommandArgs;
import cn.ares.jvm.jdi.command.ConnectedCommand;
import cn.ares.jvm.jdi.command.Responses;
import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;
import cn.ares.jvm.jdi.eval.EvalResult;

import java.util.Map;

/**
 * eval: evaluate an expression on one frame of a frozen thread.
 */
public class EvalCommand extends ConnectedCommand {

    public EvalCommand(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    protected Map<String, Object> execute(ConnectionContext ctx, Map<String, Object> cmd,
                                          Map<String, Object> resp) {
        long threadId = CommandArgs.threadId(cmd);
        String expr = (String) cmd.get("expr");
        if (threadId < 0 || null == expr) {
            return Responses.error(resp, "InvalidArgs", "Missing 'thread_id' or 'expr'");
        }
        int frame = CommandArgs.intField(cmd, "frame", 0, 0, Integer.MAX_VALUE);
        String loaderKey = CommandArgs.classLoaderKey(cmd);

        EvalResult result = ctx.getEvaluator().evaluate(threadId, frame, expr, loaderKey);
        ctx.getExecutionController().trackFrozen(threadId);
        String error = result.getError();
        if (null != error) {
            String errorCode = result.getErrorCode();
            Responses.error(resp, null != errorCode ? errorCode : "EvalError", error);
            if (null != result.getCandidateList()) {
                resp.put("candidates", result.getCandidateList());
            }
            return resp;
        }
        resp.put("status", "ok");
        resp.put("value", result.getValue());
        resp.put("type", result.getType());
        return resp;
    }
}
