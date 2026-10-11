package cn.ares.jvm.jdi.command;

import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;

import java.util.Map;

/**
 * A command that needs a live connection. The connection is read once and handed to the
 * command, which must keep using that snapshot rather than the current one.
 */
public abstract class ConnectedCommand implements Command {

    private final ConnectionManager connectionManager;

    protected ConnectedCommand(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public final Map<String, Object> execute(Map<String, Object> cmd, Map<String, Object> resp) {
        ConnectionContext ctx = requireContext(resp);
        if (null == ctx) {
            return resp;
        }
        return execute(ctx, cmd, resp);
    }

    protected abstract Map<String, Object> execute(ConnectionContext ctx, Map<String, Object> cmd,
                                                   Map<String, Object> resp);

    /**
     * The current connection, or null after filling resp with NotConnected. Read once: the
     * caller must keep using the returned context rather than the field.
     */
    private ConnectionContext requireContext(Map<String, Object> resp) {
        ConnectionContext ctx = connectionManager.current();
        if (null == ctx || !ctx.getSession().isConnected()) {
            Responses.error(resp, "NotConnected", "Not connected to a JVM. Send 'connect' first.");
            return null;
        }
        return ctx;
    }
}
