package cn.ares.jvm.jdi.command;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Whether the client keeps serving. disconnect ends it; the HTTP main thread waits here for that.
 */
public class ClientLifecycle {

    private final ReentrantLock stopLock = new ReentrantLock();
    private final Condition stopped = stopLock.newCondition();

    private volatile boolean running = true;

    public boolean isRunning() {
        return running;
    }

    /**
     * Stop serving without waking a waiter, as the disconnect command itself does: its HTTP
     * handler calls requestStop once the response has been written.
     */
    public void markStopped() {
        running = false;
    }

    public void requestStop() {
        stopLock.lock();
        try {
            running = false;
            stopped.signalAll();
        } finally {
            stopLock.unlock();
        }
    }

    /**
     * Block until stopped. An interrupt ends the wait as well.
     */
    public void awaitStop() {
        stopLock.lock();
        try {
            while (running) {
                try {
                    stopped.await(1000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            stopLock.unlock();
        }
    }
}
