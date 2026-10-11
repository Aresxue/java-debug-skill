package cn.ares.jvm.jdi.command.handler;

import cn.ares.jvm.jdi.command.Command;
import cn.ares.jvm.jdi.command.CommandArgs;
import cn.ares.jvm.jdi.command.Responses;
import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;
import cn.ares.jvm.jdi.session.ClassLoaderResolver;
import cn.ares.jvm.jdi.session.JvmSession;

import java.io.IOException;
import java.util.Map;

/**
 * connect: attach to a target JVM and start its event loop.
 */
public class ConnectCommand implements Command {

    private final ConnectionManager connectionManager;

    public ConnectCommand(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> cmd, Map<String, Object> resp) {
        return connectionManager.callLocked(() -> connectLocked(cmd, resp));
    }

    private Map<String, Object> connectLocked(Map<String, Object> cmd, Map<String, Object> resp) {
        ConnectionContext previous = connectionManager.current();
        if (null != previous && previous.getSession().isConnected()) {
            // A second connect would abandon a live VM with its frozen threads, which then
            // nobody could resume
            return Responses.error(resp, "AlreadyConnected", "Already connected to "
                    + previous.getSession().getVmName() + ". Send 'disconnect' before connecting to another JVM");
        }
        String host = (String) cmd.get("host");
        if (null == host || null == cmd.get("port")) {
            return Responses.error(resp, "InvalidArgs", "Missing 'host' or 'port'");
        }
        int port = CommandArgs.intField(cmd, "port", -1, 1, 65535);

        // Reconnecting after the target died: retire the previous generation first. Commands
        // still running on it keep their own context, whose session only ever sees the dead VM,
        // so they fail there instead of reaching the new one
        if (null != previous && !connectionManager.retire(previous)) {
            return Responses.error(resp, "ShutdownPending", "The event loop of the previous connection"
                    + " has not exited yet. Retry connect in a few seconds");
        }
        connectionManager.clear();

        JvmSession session = new JvmSession();
        ClassLoaderResolver loaderResolver = new ClassLoaderResolver();
        try {
            session.connect(host, port);
        } catch (IOException e) {
            return Responses.error(resp, "ConnectFailed", e.getMessage());
        }

        ConnectionContext ctx = new ConnectionContext(session, loaderResolver);
        ctx.getEventLoop().start();
        connectionManager.install(ctx);

        resp.put("status", "ok");
        resp.put("vm_name", session.getVmName());
        resp.put("vm_version", session.getVmVersion());
        return resp;
    }
}
