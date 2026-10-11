package cn.ares.jvm.jdi.eval;

import com.sun.jdi.Method;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The methods eval refuses to invoke, checked before every invoke whichever route it takes.
 */
final class InvocationGuard {

    /**
     * Methods eval refuses to invoke, keyed by declaring class + "#" + method name. One mistyped
     * expression here kills or hijacks the debugged JVM. This guards against mistakes, not a
     * determined caller: eval can still reach other code with side effects
     */
    private static final Set<String> DENIED_METHOD_SET = new HashSet<>(Arrays.asList(
            "java.lang.System#exit",
            "java.lang.Runtime#exit",
            "java.lang.Runtime#halt",
            "java.lang.Runtime#exec",
            "java.lang.ProcessBuilder#start",
            "java.lang.Thread#stop",
            "java.lang.Thread#suspend"));

    private InvocationGuard() {
    }

    static void checkInvocable(Method method) {
        String key = method.declaringType().name() + "#" + method.name();
        if (DENIED_METHOD_SET.contains(key)) {
            throw new IllegalArgumentException("Refusing to invoke " + key.replace('#', '.')
                    + ": it would terminate the debugged JVM, spawn a process or stop a thread");
        }
    }
}
