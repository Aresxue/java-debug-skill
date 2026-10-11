package cn.ares.jvm.jdi.command.handler;

import cn.ares.jvm.jdi.command.ClientLifecycle;
import cn.ares.jvm.jdi.command.Command;
import cn.ares.jvm.jdi.connection.ConnectionManager;

import java.util.Map;

/**
 * disconnect: retire the connection and stop the client.
 */
public class DisconnectCommand implements Command {

    private final ConnectionManager connectionManager;

    private final ClientLifecycle lifecycle;

    public DisconnectCommand(ConnectionManager connectionManager, ClientLifecycle lifecycle) {
        this.connectionManager = connectionManager;
        this.lifecycle = lifecycle;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> cmd, Map<String, Object> resp) {
        lifecycle.markStopped();
        connectionManager.shutdown();
        resp.put("status", "ok");
        return resp;
    }
}
