package cn.ares.jvm.jdi.command;

import java.util.Map;

/**
 * Error responses: status, error code and message, in that order.
 */
public final class Responses {

    private Responses() {
    }

    public static Map<String, Object> error(Map<String, Object> resp, String code, String message) {
        resp.put("status", "error");
        resp.put("error", code);
        resp.put("message", message);
        return resp;
    }
}
