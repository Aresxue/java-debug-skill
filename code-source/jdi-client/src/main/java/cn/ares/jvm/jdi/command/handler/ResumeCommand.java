package cn.ares.jvm.jdi.command.handler;

import cn.ares.jvm.jdi.command.CommandArgs;
import cn.ares.jvm.jdi.command.ConnectedCommand;
import cn.ares.jvm.jdi.command.Responses;
import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;

import java.util.Map;

/**
 * resume: release a frozen thread.
 */
public class ResumeCommand extends ConnectedCommand {

    public ResumeCommand(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    protected Map<String, Object> execute(ConnectionContext ctx, Map<String, Object> cmd,
                                          Map<String, Object> resp) {
        long threadId = CommandArgs.threadId(cmd);
        if (threadId < 0) {
            return Responses.error(resp, "InvalidArgs", "Missing 'thread_id'");
        }
        Boolean released = ctx.getExecutionController().resume(threadId);
        if (null == released) {
            return Responses.error(resp, "InvalidThread", "Thread not found: " + threadId);
        }
        resp.put("status", "ok");
        if (Boolean.FALSE.equals(released)) {
            resp.put("note", "Thread was not suspended, nothing to resume");
        }
        return resp;
    }
}
