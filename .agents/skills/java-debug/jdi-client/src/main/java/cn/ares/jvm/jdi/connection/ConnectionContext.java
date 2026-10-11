package cn.ares.jvm.jdi.connection;

import cn.ares.jvm.jdi.breakpoint.BreakpointManager;
import cn.ares.jvm.jdi.eval.ExpressionEvaluator;
import cn.ares.jvm.jdi.execution.EventLoop;
import cn.ares.jvm.jdi.execution.ExecutionController;
import cn.ares.jvm.jdi.inspect.StackInspector;
import cn.ares.jvm.jdi.inspect.ThreadLister;
import cn.ares.jvm.jdi.session.ClassLoaderResolver;
import cn.ares.jvm.jdi.session.JvmSession;
import cn.ares.jvm.jdi.session.ThreadRegistry;

/**
 * Everything one connection owns. A reconnect builds a new one around a new session instead
 * of re-pointing shared fields, so a command still running on the old connection keeps
 * talking to the dead VM and fails there: it can neither resume a thread of the new VM that
 * happens to reuse its thread id nor hold the new connection's step/eval permits.
 */
public final class ConnectionContext {

    private final JvmSession session;
    private final ThreadRegistry threadRegistry;
    private final StackInspector stackInspector;
    private final ThreadLister threadLister;
    private final ExpressionEvaluator evaluator;
    private final BreakpointManager breakpointManager;
    private final ExecutionController executionController;
    private final EventLoop eventLoop;
    private final CommandSlots commandSlots = new CommandSlots();

    public ConnectionContext(JvmSession session, ClassLoaderResolver loaderResolver) {
        this.session = session;
        this.threadRegistry = new ThreadRegistry(session);
        this.stackInspector = new StackInspector(threadRegistry, loaderResolver);
        this.threadLister = new ThreadLister(session);
        this.evaluator = new ExpressionEvaluator(session, threadRegistry, loaderResolver);
        this.breakpointManager = new BreakpointManager(session, loaderResolver);
        this.executionController = new ExecutionController(session, threadRegistry);
        this.eventLoop = new EventLoop(session, threadRegistry, breakpointManager, stackInspector,
                executionController);
    }

    public JvmSession getSession() {
        return session;
    }

    public ThreadRegistry getThreadRegistry() {
        return threadRegistry;
    }

    public StackInspector getStackInspector() {
        return stackInspector;
    }

    public ThreadLister getThreadLister() {
        return threadLister;
    }

    public ExpressionEvaluator getEvaluator() {
        return evaluator;
    }

    public BreakpointManager getBreakpointManager() {
        return breakpointManager;
    }

    public ExecutionController getExecutionController() {
        return executionController;
    }

    public EventLoop getEventLoop() {
        return eventLoop;
    }

    public CommandSlots getCommandSlots() {
        return commandSlots;
    }
}
