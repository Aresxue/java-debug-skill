package cn.ares.jvm.jdi.command.handler;

import cn.ares.jvm.jdi.command.CommandArgs;
import cn.ares.jvm.jdi.command.ConnectedCommand;
import cn.ares.jvm.jdi.command.Responses;
import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;

import java.util.Map;

/**
 * locals: the local variables of one frame of a frozen thread.
 */
public class LocalsCommand extends ConnectedCommand {

    public LocalsCommand(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    protected Map<String, Object> execute(ConnectionContext ctx, Map<String, Object> cmd,
                                          Map<String, Object> resp) {
        long threadId = CommandArgs.threadId(cmd);
        if (threadId < 0) {
            return Responses.error(resp, "InvalidArgs", "Missing 'thread_id'");
        }
        int frame = CommandArgs.intField(cmd, "frame", 0, 0, Integer.MAX_VALUE);

        Map<String, String> localMap = ctx.getStackInspector().collectLocals(threadId, frame);
        if (null == localMap) {
            return Responses.error(resp, "InvalidThread", "Thread not found or not suspended: " + threadId);
        }
        resp.put("status", "ok");
        resp.put("locals", localMap);
        return resp;
    }
}
