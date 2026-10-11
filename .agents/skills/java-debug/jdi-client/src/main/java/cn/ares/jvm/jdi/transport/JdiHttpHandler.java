package cn.ares.jvm.jdi.transport;

import cn.ares.jvm.jdi.command.ClientLifecycle;
import cn.ares.jvm.jdi.command.CommandDispatcher;
import cn.ares.jvm.jdi.connection.ConnectionContext;
import cn.ares.jvm.jdi.connection.ConnectionManager;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * POST runs a command; GET is a health check. Requests must name a loopback Host, carry no Origin,
 * and POST with Content-Type application/json, which keeps browser pages out.
 */
class JdiHttpHandler implements HttpHandler {

  /**
   * Request body cap. Commands are a few hundred bytes; anything near this is a mistake
   */
  private static final int MAX_BODY_BYTES = 1024 * 1024;

  /**
   * Host names a loopback client can legitimately send. A DNS rebinding page reaches 127.0.0.1
   * under its own domain, so its Host header names that domain and is refused here
   */
  private static final Set<String> ALLOWED_HOST_SET = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("127.0.0.1", "localhost", "[::1]")));

  private static final String JSON_MEDIA_TYPE = "application/json";

  private final JsonProtocol json;

  private final CommandDispatcher dispatcher;

  private final ConnectionManager connectionManager;

  private final ClientLifecycle lifecycle;

  JdiHttpHandler(JsonProtocol json, CommandDispatcher dispatcher, ConnectionManager connectionManager, ClientLifecycle lifecycle) {
    this.json = json;
    this.dispatcher = dispatcher;
    this.connectionManager = connectionManager;
    this.lifecycle = lifecycle;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    // Browsers are the only callers loopback binding does not keep out: refuse foreign Host
    // (DNS rebinding) and any Origin (cross-site pages; curl never sends one)
    Headers headers = exchange.getRequestHeaders();
    String hostHeader = headers.getFirst("Host");
    if (null == hostHeader || !ALLOWED_HOST_SET.contains(hostNameOf(hostHeader))) {
      sendError(exchange, 403, "ForbiddenHost", "Host header must be 127.0.0.1 or localhost");
      return;
    }
    if (null != headers.getFirst("Origin")) {
      sendError(exchange, 403, "ForbiddenOrigin", "Browser requests are not accepted");
      return;
    }

    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      // Also accept GET for simple health check
      if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "ok");
        ConnectionContext ctx = connectionManager.current();
        status.put("connected", null != ctx && ctx.getSession().isConnected());
        sendJsonResponse(exchange, 200, status);
        return;
      }
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("status", "error");
      err.put("error", "MethodNotAllowed");
      err.put("message", "Use POST with JSON body");
      sendJsonResponse(exchange, 405, err);
      return;
    }

    // A JSON content type is not a CORS simple request, so a browser has to preflight with OPTIONS,
    // which gets 405 above; text/plain or form posts would otherwise run blind
    if (!isJsonContentType(headers.getFirst("Content-Type"))) {
      sendError(exchange, 415, "UnsupportedMediaType", "Content-Type must be " + JSON_MEDIA_TYPE);
      return;
    }

    // Read request body
    String body = readBody(exchange.getRequestBody());
    if (null == body) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("status", "error");
      err.put("error", "BodyTooLarge");
      err.put("message", "Request body exceeds " + MAX_BODY_BYTES + " bytes");
      sendJsonResponse(exchange, 413, err);
      return;
    }

    if (body.isEmpty()) {
      Map<String, Object> err = new LinkedHashMap<>();
      err.put("status", "error");
      err.put("error", "EmptyBody");
      err.put("message", "Request body is empty");
      sendJsonResponse(exchange, 400, err);
      return;
    }

    // Parse and dispatch
    Map<String, Object> response;
    String cmd = null;
    try {
      Map<String, Object> command = json.parse(body);
      cmd = (String) command.get("cmd");
      response = dispatcher.dispatch(command);
    } catch (Exception e) {
      response = new LinkedHashMap<>();
      response.put("status", "error");
      response.put("error", "ParseError");
      response.put("message", "Failed to parse command: " + e.getMessage());
    }

    sendJsonResponse(exchange, 200, response);

    // If disconnect was called, stop the server
    if ("disconnect".equals(cmd)) {
      lifecycle.requestStop();
    }
  }

  /**
   * Host header without its port, lower-cased; IPv6 literals keep the colons inside brackets.
   */
  private static String hostNameOf(String hostHeader) {
    String host = hostHeader.trim();
    int portSeparator = host.lastIndexOf(':');
    if (portSeparator > host.lastIndexOf(']')) {
      host = host.substring(0, portSeparator);
    }
    return host.toLowerCase(Locale.ROOT);
  }

  /**
   * Media type part equals application/json; parameters such as charset are ignored.
   */
  private static boolean isJsonContentType(String contentType) {
    if (null == contentType) {
      return false;
    }
    int paramStart = contentType.indexOf(';');
    String mediaType = paramStart < 0 ? contentType : contentType.substring(0, paramStart);
    return JSON_MEDIA_TYPE.equalsIgnoreCase(mediaType.trim());
  }

  private void sendError(HttpExchange exchange, int code, String error, String message) throws IOException {
    Map<String, Object> err = new LinkedHashMap<>();
    err.put("status", "error");
    err.put("error", error);
    err.put("message", message);
    sendJsonResponse(exchange, code, err);
  }

  /**
   * Read the whole body as UTF-8, or null once it exceeds MAX_BODY_BYTES.
   */
  private String readBody(InputStream in) throws IOException {
    try (InputStream body = in) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = body.read(buffer)) != -1) {
        if (out.size() + n > MAX_BODY_BYTES) {
          return null;
        }
        out.write(buffer, 0, n);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
    }
  }

  private void sendJsonResponse(HttpExchange exchange, int code, Map<String, Object> response) throws IOException {
    byte[] bytes = json.serialize(response).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(code, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.getResponseBody().close();
  }
}
