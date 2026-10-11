package cn.ares.jvm.jdi.command;

import cn.ares.jvm.jdi.session.ClassLoaderResolver;

import java.util.Map;

/**
 * Typed reads of command fields, refusing values that would otherwise reach JDI as something
 * the caller did not mean.
 */
public final class CommandArgs {

    private CommandArgs() {
    }

    /**
     * Optional eval field choosing among same-named classes from different classloaders: the
     * class_loader_id reported in a hit's location or in candidates, or "bootstrap".
     */
    public static String classLoaderKey(Map<String, Object> cmd) {
        Object obj = cmd.get("class_loader_id");
        if (null == obj) {
            return null;
        }
        if (obj instanceof Integer || obj instanceof Long) {
            return String.valueOf(obj);
        }
        if (ClassLoaderResolver.BOOTSTRAP_LOADER.equals(obj)) {
            return ClassLoaderResolver.BOOTSTRAP_LOADER;
        }
        throw new IllegalArgumentException("Field 'class_loader_id' must be an integer or \""
                + ClassLoaderResolver.BOOTSTRAP_LOADER + "\", got: " + obj);
    }

    public static long threadId(Map<String, Object> cmd) {
        Object obj = cmd.get("thread_id");
        if (null == obj) {
            return -1;
        }
        if (obj instanceof Integer || obj instanceof Long) {
            return ((Number) obj).longValue();
        }
        // Truncating 42.9 to thread 42 would silently answer with another thread's state
        throw new IllegalArgumentException("Field 'thread_id' must be an integer, got: " + obj);
    }

    /**
     * Read an optional integer field, rejecting non-integers and out-of-range values instead of
     * letting them reach JDI as a nonsensical argument.
     */
    public static int intField(Map<String, Object> cmd, String name, int defaultValue, int min, int max) {
        Object obj = cmd.get(name);
        if (null == obj) {
            return defaultValue;
        }
        boolean integral = obj instanceof Integer || obj instanceof Long;
        if (integral == false) {
            throw new IllegalArgumentException("Field '" + name + "' must be an integer, got: " + obj);
        }
        long value = ((Number) obj).longValue();
        if (value < min || value > max) {
            throw new IllegalArgumentException("Field '" + name + "' must be within ["
                    + min + ", " + max + "], got: " + value);
        }
        return (int) value;
    }
}
