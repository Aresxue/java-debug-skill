package cn.ares.jvm.jdi.command.handler;

import cn.ares.jvm.jdi.command.CommandArgs;
import cn.ares.jvm.jdi.command.ConnectedCommand;
import cn.ares.jvm.jdi.command.Responses;
import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;

import java.util.List;
import java.util.Map;

/**
 * stack: the frames of a frozen thread.
 */
public class StackCommand extends ConnectedCommand {

    public StackCommand(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    protected Map<String, Object> execute(ConnectionContext ctx, Map<String, Object> cmd,
                                          Map<String, Object> resp) {
        long threadId = CommandArgs.threadId(cmd);
        if (threadId < 0) {
            return Responses.error(resp, "InvalidArgs", "Missing 'thread_id'");
        }
        int maxFrames = CommandArgs.intField(cmd, "max_frames", 20, 1, 500);

        List<Map<String, Object>> stackList = ctx.getStackInspector().collectStack(threadId, maxFrames);
        if (null == stackList) {
            return Responses.error(resp, "InvalidThread", "Thread not found or not suspended: " + threadId);
        }
        resp.put("status", "ok");
        resp.put("stack", stackList);
        return resp;
    }
}
