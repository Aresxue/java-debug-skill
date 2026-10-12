package cn.ares.jvm.jdi;

import cn.ares.jvm.jdi.command.BlockingCommand;
import cn.ares.jvm.jdi.command.ClientLifecycle;
import cn.ares.jvm.jdi.command.Command;
import cn.ares.jvm.jdi.command.CommandDispatcher;
import cn.ares.jvm.jdi.command.handler.ClearBreakpointCommand;
import cn.ares.jvm.jdi.command.handler.ConnectCommand;
import cn.ares.jvm.jdi.command.handler.DisconnectCommand;
import cn.ares.jvm.jdi.command.handler.EvalCommand;
import cn.ares.jvm.jdi.command.handler.LocalsCommand;
import cn.ares.jvm.jdi.command.handler.ResumeCommand;
import cn.ares.jvm.jdi.command.handler.SetBreakpointCommand;
import cn.ares.jvm.jdi.command.handler.StackCommand;
import cn.ares.jvm.jdi.command.handler.StepCommand;
import cn.ares.jvm.jdi.command.handler.ThreadsCommand;
import cn.ares.jvm.jdi.command.handler.WaitBreakpointCommand;
import cn.ares.jvm.jdi.connection.ConnectionManager;
import cn.ares.jvm.jdi.transport.HttpTransport;
import cn.ares.jvm.jdi.transport.JsonProtocol;
import cn.ares.jvm.jdi.transport.StdioTransport;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JDI Client main entry point. Two modes: 1. stdin/stdout JSON Lines (default) — pipe-based
 * interaction 2. HTTP Server mode (--server) — curl-based interaction, process stays alive
 */
public class JdiClient {

  private final JsonProtocol json = new JsonProtocol();

  private final ConnectionManager connectionManager = new ConnectionManager();

  private final ClientLifecycle lifecycle = new ClientLifecycle();

  private final CommandDispatcher dispatcher;

  private final StdioTransport stdioTransport;

  public JdiClient() {
    PrintStream stdout = System.out;
    // Redirect System.out to stderr to prevent JDI messages from corrupting protocol
    System.setOut(System.err);
    this.dispatcher = new CommandDispatcher(buildCommandMap());
    this.stdioTransport = new StdioTransport(stdout, json, dispatcher, lifecycle,
        connectionManager);
  }

  public static void main(String[] args) {
    JdiClient client = new JdiClient();
    client.run(ClientOptions.parse(args));
  }

  private Map<String, Command> buildCommandMap() {
    Map<String, Command> commandMap = new HashMap<>();
    commandMap.put("connect", new ConnectCommand(connectionManager));
    commandMap.put("disconnect", new DisconnectCommand(connectionManager, lifecycle));
    commandMap.put("set-breakpoint", new SetBreakpointCommand(connectionManager));
    commandMap.put("clear-breakpoint", new ClearBreakpointCommand(connectionManager));
    commandMap.put("wait-breakpoint", new WaitBreakpointCommand(connectionManager));
    commandMap.put("threads", new ThreadsCommand(connectionManager));
    commandMap.put("resume", new ResumeCommand(connectionManager));
    commandMap.put("step",
        new BlockingCommand(connectionManager, new StepCommand(connectionManager)));
    commandMap.put("stack", new StackCommand(connectionManager));
    commandMap.put("locals", new LocalsCommand(connectionManager));
    commandMap.put("eval",
        new BlockingCommand(connectionManager, new EvalCommand(connectionManager)));
    return commandMap;
  }

  private void run(ClientOptions options) {
    Runtime.getRuntime().addShutdownHook(new Thread(connectionManager::shutdown));

    // Auto-connect if args provided
    if (options.hasAutoConnect()) {
      Map<String, Object> cmd = new LinkedHashMap<>();
      cmd.put("id", 0);
      cmd.put("cmd", "connect");
      cmd.put("host", options.getAutoHost());
      cmd.put("port", options.getAutoPort());
      Map<String, Object> resp = dispatcher.dispatch(cmd);
      if (options.isServerMode()) {
        System.err.println(json.serialize(resp));
      } else {
        stdioTransport.writeResponse(resp);
      }
      if ("error".equals(resp.get("status"))) {
        return;
      }
    }

    if (options.isServerMode()) {
      new HttpTransport(options.getServerPort(), json, dispatcher, connectionManager,
          lifecycle).run();
    } else {
      stdioTransport.run();
    }
  }

}

/**
 * Command line options.
 */
final class ClientOptions {

  private String autoHost;
  private int autoPort;
  private boolean serverMode = false;
  private int serverPort = 15005;

  private ClientOptions() {
  }

  static ClientOptions parse(String[] args) {
    ClientOptions options = new ClientOptions();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--host":
          if (i + 1 < args.length) {
            options.autoHost = args[++i];
          }
          break;
        case "--port":
          if (i + 1 < args.length) {
            options.autoPort = parsePort(args[++i], "--port");
          }
          break;
        case "--server":
          options.serverMode = true;
          break;
        case "--server-port":
          if (i + 1 < args.length) {
            options.serverPort = parsePort(args[++i], "--server-port");
          }
          break;
      }
    }
    return options;
  }

  private static int parsePort(String value, String option) {
    try {
      int port = Integer.parseInt(value);
      if (port < 1 || port > 65535) {
        throw new NumberFormatException(value);
      }
      return port;
    } catch (NumberFormatException e) {
      System.err.println("Invalid value for " + option + ": '" + value
          + "' is not a port number (1-65535)");
      System.exit(2);
      return -1;
    }
  }

  /**
   * Both --host and --port given: connect before serving.
   */
  boolean hasAutoConnect() {
    return null != autoHost && autoPort > 0;
  }

  String getAutoHost() {
    return autoHost;
  }

  int getAutoPort() {
    return autoPort;
  }

  boolean isServerMode() {
    return serverMode;
  }

  int getServerPort() {
    return serverPort;
  }

}
