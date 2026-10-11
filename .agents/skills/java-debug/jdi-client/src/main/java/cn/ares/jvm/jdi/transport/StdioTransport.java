package cn.ares.jvm.jdi.transport;

import cn.ares.jvm.jdi.command.ClientLifecycle;
import cn.ares.jvm.jdi.command.CommandDispatcher;
import cn.ares.jvm.jdi.connection.ConnectionManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Default mode: one JSON command per stdin line, one JSON response per stdout line.
 */
public class StdioTransport {

  private final ReentrantLock stdoutLock = new ReentrantLock();

  private final PrintStream stdout;

  private final JsonProtocol json;

  private final CommandDispatcher dispatcher;

  private final ClientLifecycle lifecycle;

  private final ConnectionManager connectionManager;

  public StdioTransport(PrintStream stdout, JsonProtocol json, CommandDispatcher dispatcher, ClientLifecycle lifecycle, ConnectionManager connectionManager) {
    this.stdout = stdout;
    this.json = json;
    this.dispatcher = dispatcher;
    this.lifecycle = lifecycle;
    this.connectionManager = connectionManager;
  }

  /**
   * Serve stdin until it closes or disconnect stops the client, then retire the connection.
   */
  public void run() {
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
    String line;
    try {
      while (lifecycle.isRunning() && (line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        try {
          Map<String, Object> command = json.parse(line);
          Map<String, Object> response = dispatcher.dispatch(command);
          writeResponse(response);
        } catch (Exception e) {
          Map<String, Object> errResp = new LinkedHashMap<>();
          errResp.put("status", "error");
          errResp.put("error", "ParseError");
          errResp.put("message", "Failed to parse command: " + e.getMessage());
          writeResponse(errResp);
        }
      }
    } catch (IOException ioException) {
      // stdin closed
    }
    connectionManager.shutdown();
  }

  public void writeResponse(Map<String, Object> response) {
    stdoutLock.lock();
    try {
      stdout.println(json.serialize(response));
      stdout.flush();
    } finally {
      stdoutLock.unlock();
    }
  }
}
