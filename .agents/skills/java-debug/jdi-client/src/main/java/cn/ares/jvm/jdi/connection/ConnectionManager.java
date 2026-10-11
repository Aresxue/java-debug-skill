package cn.ares.jvm.jdi.connection;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Holds the current connection and serializes its lifecycle: connect, retire and shutdown.
 */
public class ConnectionManager {

    /**
     * Serializes connect against disconnect/shutdown. Without it two concurrent connects both
     * pass the isConnected() check, and a disconnect can dispose the VM mid-connect
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    /**
     * The current connection, published by the connect handler and read by every other HTTP
     * thread. A handler reads it once and uses that snapshot to the end, so a reconnect midway
     * cannot switch a command onto the new VM, where thread ids may repeat
     */
    private volatile ConnectionContext context;

    public ConnectionContext current() {
        return context;
    }

    /**
     * Run a lifecycle change under the lifecycle lock.
     */
    public <T> T callLocked(Supplier<T> action) {
        lifecycleLock.lock();
        try {
            return action.get();
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void install(ConnectionContext ctx) {
        context = ctx;
    }

    public void clear() {
        context = null;
    }

    /**
     * Stop the event loop and dispose the VM of a connection. Returns whether the loop exited.
     */
    public boolean retire(ConnectionContext ctx) {
        boolean exited = ctx.getEventLoop().stop();
        ctx.getThreadRegistry().clear();
        ctx.getSession().disconnect();
        return exited;
    }

    public void shutdown() {
        lifecycleLock.lock();
        try {
            ConnectionContext ctx = context;
            if (null != ctx) {
                retire(ctx);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }
}
