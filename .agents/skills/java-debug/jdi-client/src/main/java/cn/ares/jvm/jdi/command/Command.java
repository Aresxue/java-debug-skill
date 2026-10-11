package cn.ares.jvm.jdi.command;

import java.util.Map;

/**
 * One protocol command. Fills resp, which already carries the request id, and returns it.
 */
public interface Command {

    Map<String, Object> execute(Map<String, Object> cmd, Map<String, Object> resp);
}
