package cn.ares.jvm.jdi.command.handler;

import cn.ares.jvm.jdi.command.ConnectedCommand;
import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;

import java.util.Map;

/**
 * threads: list every thread of the target.
 */
public class ThreadsCommand extends ConnectedCommand {

    public ThreadsCommand(ConnectionManager connectionManager) {
        super(connectionManager);
    }

    @Override
    protected Map<String, Object> execute(ConnectionContext ctx, Map<String, Object> cmd,
                                          Map<String, Object> resp) {
        resp.put("status", "ok");
        resp.put("threads", ctx.getThreadLister().listThreads());
        return resp;
    }
}
