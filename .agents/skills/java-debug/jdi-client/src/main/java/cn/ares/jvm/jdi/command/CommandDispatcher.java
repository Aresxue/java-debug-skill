package cn.ares.jvm.jdi.command;

import com.sun.jdi.VMDisconnectedException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes a parsed request to its command and maps what escapes it to an error code.
 */
public class CommandDispatcher {

    private final Map<String, Command> commandMap;

    public CommandDispatcher(Map<String, Command> commandMap) {
        this.commandMap = commandMap;
    }

    /**
     * A non-string cmd field fails the cast before the try below, so the transport reports it
     * as ParseError.
     */
    public Map<String, Object> dispatch(Map<String, Object> command) {
        String cmd = (String) command.get("cmd");
        Object idObj = command.get("id");

        Map<String, Object> response = new LinkedHashMap<>();
        if (null != idObj) {
            response.put("id", idObj);
        }

        if (null == cmd) {
            return Responses.error(response, "InvalidCommand", "Missing 'cmd' field");
        }

        try {
            Command handler = commandMap.get(cmd);
            if (null == handler) {
                return Responses.error(response, "UnknownCommand", "Unknown command: " + cmd);
            }
            return handler.execute(command, response);
        } catch (IllegalArgumentException e) {
            // Argument validation failures: report them as such instead of as an internal error
            return Responses.error(response, "InvalidArgs", e.getMessage());
        } catch (VMDisconnectedException e) {
            // A disconnect racing a command that already passed requireContext
            return Responses.error(response, "VmDisconnected", "Target JVM disconnected while the command was running");
        } catch (Exception e) {
            return Responses.error(response, "InternalError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
