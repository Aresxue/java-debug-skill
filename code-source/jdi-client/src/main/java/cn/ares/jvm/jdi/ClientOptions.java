package cn.ares.jvm.jdi;

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
