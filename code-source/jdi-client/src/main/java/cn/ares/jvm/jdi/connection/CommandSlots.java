package cn.ares.jvm.jdi.connection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The step/eval permits of one connection, and the commands currently holding them.
 */
public class CommandSlots {

    /**
     * step and eval can hold a worker for a long time (eval has no timeout at all: an invocation
     * that hits a breakpoint or a lock waits until someone resumes it). Capped so that one worker
     * stays free for wait-breakpoint, which a newer wait supersedes and so never holds more than
     * one, and one for resume/disconnect/clear-breakpoint: the commands that unblock the others
     */
    public static final int MAX_BLOCKING_COMMANDS = 2;

    private final Semaphore blockingPermits = new Semaphore(MAX_BLOCKING_COMMANDS);

    /**
     * step/eval commands currently holding a permit, listed in a Busy answer so the caller
     * knows which thread to resume
     */
    private final Map<Long, Map<String, Object>> inFlightMap = new ConcurrentHashMap<>();
    private final AtomicLong inFlightSeq = new AtomicLong();

    public boolean tryAcquire() {
        return blockingPermits.tryAcquire();
    }

    public List<Map<String, Object>> inFlightList() {
        return new ArrayList<>(inFlightMap.values());
    }

    /**
     * Record a command that has just taken a permit. Returns the key release() takes.
     */
    public long track(Map<String, Object> info) {
        long seq = inFlightSeq.incrementAndGet();
        inFlightMap.put(seq, info);
        return seq;
    }

    public void release(long seq) {
        inFlightMap.remove(seq);
        blockingPermits.release();
    }
}
