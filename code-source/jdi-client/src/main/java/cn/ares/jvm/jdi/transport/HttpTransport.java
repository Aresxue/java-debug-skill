package cn.ares.jvm.jdi.transport;

import cn.ares.jvm.jdi.command.ClientLifecycle;
import cn.ares.jvm.jdi.command.CommandDispatcher;
import cn.ares.jvm.jdi.connection.CommandSlots;
import cn.ares.jvm.jdi.connection.ConnectionManager;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * --server mode: one JSON command per POST body, served on loopback until disconnect.
 */
public class HttpTransport {

  /**
   * Handlers must run in parallel: wait-breakpoint blocks for minutes, and on the single default
   * dispatcher thread it would also block health checks, breakpoint changes and commands aimed at
   * other already-frozen threads
   */
  private static final int HTTP_POOL_SIZE = CommandSlots.MAX_BLOCKING_COMMANDS + 2;

  private static final AtomicInteger HTTP_THREAD_SEQ = new AtomicInteger(1);

  /**
   * Deadline in seconds for receiving a whole request. The JDK HttpServer has none by default, so a
   * few clients trickling a body would hold every worker. Only the request phase is capped:
   * maxRspTime also covers handler time and would cut wait-breakpoint long polls
   */
  private static final String MAX_REQ_TIME_PROPERTY = "sun.net.httpserver.maxReqTime";
  private static final String DEFAULT_MAX_REQ_TIME_SECONDS = "30";

  private final int serverPort;

  private final JsonProtocol json;

  private final CommandDispatcher dispatcher;

  private final ConnectionManager connectionManager;

  private final ClientLifecycle lifecycle;

  public HttpTransport(int serverPort, JsonProtocol json, CommandDispatcher dispatcher,
      ConnectionManager connectionManager, ClientLifecycle lifecycle) {
    this.serverPort = serverPort;
    this.json = json;
    this.dispatcher = dispatcher;
    this.connectionManager = connectionManager;
    this.lifecycle = lifecycle;
  }

  /**
   * Serve until disconnect stops the client.
   */
  public void run() {
    ExecutorService httpExecutor = null;
    try {
      // Loopback only: this port is an unauthenticated JDI control plane and 'eval' runs
      // arbitrary code inside the debugged JVM. Remote drivers go through an SSH tunnel.
      // Pinned to IPv4: getLoopbackAddress() turns into ::1 under preferIPv6Addresses,
      // while every documented curl and ssh -L example targets 127.0.0.1
      InetAddress bindAddress = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
      // Read once when the HttpServer implementation loads, so it must be set before create
      if (null == System.getProperty(MAX_REQ_TIME_PROPERTY)) {
        System.setProperty(MAX_REQ_TIME_PROPERTY, DEFAULT_MAX_REQ_TIME_SECONDS);
      }
      HttpServer server = HttpServer.create(new InetSocketAddress(bindAddress, serverPort), 0);
      server.createContext("/", new JdiHttpHandler(json, dispatcher, connectionManager, lifecycle));
      httpExecutor = Executors.newFixedThreadPool(HTTP_POOL_SIZE, runnable -> {
        Thread worker = new Thread(runnable, "Jdi-Http-" + HTTP_THREAD_SEQ.getAndIncrement());
        worker.setDaemon(true);
        return worker;
      });
      server.setExecutor(httpExecutor);
      server.start();
      System.err.println("JDI Client HTTP server started on " + bindAddress.getHostAddress() + ":" + serverPort);
      System.err.println("Usage: curl -s http://127.0.0.1:" + serverPort + " -H 'Content-Type: application/json' -d '{\"cmd\":\"...\"}' ");

      // Block main thread until shutdown
      lifecycle.awaitStop();
      server.stop(1);
    } catch (IOException ioException) {
      System.err.println("Failed to start HTTP server: " + ioException.getMessage());
    } finally {
      if (null != httpExecutor) {
        httpExecutor.shutdownNow();
      }
    }
  }
}
