package cn.ares.jvm.jdi.command;

import cn.ares.jvm.jdi.connection.CommandSlots;
import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs a command that may block for long under a permit, or answers Busy at once instead of
 * letting it queue: a queued blocking command would take the worker the unblocking ones need.
 * Permits belong to the connection, so commands stuck on a dead VM cannot starve a new one.
 */
public class BlockingCommand extends ConnectedCommand {

    private final ConnectedCommand delegate;

    public BlockingCommand(ConnectionManager connectionManager, ConnectedCommand delegate) {
        super(connectionManager);
        this.delegate = delegate;
    }

    @Override
    protected Map<String, Object> execute(ConnectionContext ctx, Map<String, Object> cmd,
                                          Map<String, Object> resp) {
        CommandSlots slots = ctx.getCommandSlots();
        if (!slots.tryAcquire()) {
            Responses.error(resp, "Busy", "At most " + CommandSlots.MAX_BLOCKING_COMMANDS
                    + " step/eval commands run at once, so that resume and disconnect always have a worker");
            resp.put("in_flight", slots.inFlightList());
            resp.put("busy_hint", "An eval that does not return usually hit a breakpoint or waits on"
                    + " a lock: run wait-breakpoint and resume the thread it reports, then retry");
            return resp;
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("cmd", cmd.get("cmd"));
        info.put("thread_id", cmd.get("thread_id"));
        long seq = slots.track(info);
        try {
            return delegate.execute(ctx, cmd, resp);
        } finally {
            slots.release(seq);
        }
    }
}
