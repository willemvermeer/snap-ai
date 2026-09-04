package snap.cli

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch
import snap.SnapError
import snap.json.JsonWriter
import snap.repository.RepositoryCodec
import snap.workspace.RepositoryFile

/**
 * `snap --serve [port]` (SPEC.md §7.9, §9). Grammar is zero or one plain operand, the
 * port. Unlike `commit`/`revert`, no contributor configuration is required — serving
 * reads but never authors a patch.
 */
object ServeCommand {
  private[cli] val DefaultPort = 8765
  private[cli] val ResourcePath = "/repository.json"

  /** SPEC.md §7.9: "port defaults to 8765, while 0 asks the OS to select one." */
  private[cli] def parsePort(args: Vector[String]): Int = args match {
    case Vector() => DefaultPort
    case Vector(p) =>
      p.toIntOption
        .filter(port => port >= 0 && port <= 65535)
        .getOrElse(throw SnapError(s"invalid port: $p"))
    case _ => throw SnapError("invalid command or arguments")
  }

  /**
   * SPEC.md §9's fixed resource: `GET`/`HEAD /repository.json` serve the startup
   * snapshot; every other path is `404`; every other method on this one path is `405`
   * with `Allow: GET, HEAD`. A query string makes the request-target not this exact
   * resource, so it 404s too, matching the client's own "one exact... GET" contract.
   */
  final private[cli] class SnapshotHandler(snapshot: Array[Byte]) extends HttpHandler {
    override def handle(exchange: HttpExchange): Unit =
      try {
        val uri = exchange.getRequestURI
        if (uri.getPath != ResourcePath || uri.getRawQuery != null) {
          exchange.sendResponseHeaders(404, -1)
        } else
          exchange.getRequestMethod match {
            case "GET" =>
              exchange.getResponseHeaders.add("Content-Type", "application/json; charset=utf-8")
              exchange.sendResponseHeaders(200, snapshot.length.toLong)
              val body = exchange.getResponseBody
              try body.write(snapshot)
              finally body.close()
            case "HEAD" =>
              exchange.getResponseHeaders.add("Content-Type", "application/json; charset=utf-8")
              exchange.getResponseHeaders.add("Content-Length", snapshot.length.toString)
              exchange.sendResponseHeaders(200, -1)
            case _ =>
              exchange.getResponseHeaders.add("Allow", "GET, HEAD")
              exchange.sendResponseHeaders(405, -1)
          }
      } finally exchange.close()
  }

  /**
   * Binds and starts a server for `snapshot`, without printing or waiting — the
   * reusable, directly testable half of `run`.
   */
  private[cli] def bind(port: Int, snapshot: Array[Byte]): HttpServer = {
    val server =
      try HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
      catch { case e: IOException => throw SnapError(s"failed to start server: ${e.getMessage}") }
    server.createContext("/", new SnapshotHandler(snapshot))
    server.setExecutor(null)
    server.start()
    server
  }

  def run(args: Vector[String], env: Cli.Env): Unit = {
    val port = parsePort(args)
    val snapDir = CliSupport.requireSnapDir(env)
    val repository = RepositoryFile.read(snapDir)
    val snapshot = JsonWriter.write(RepositoryCodec.encode(repository)).getBytes(UTF_8)

    val server = bind(port, snapshot)
    try {
      env.stdout.print(s"http://127.0.0.1:${server.getAddress.getPort}$ResourcePath\n")
      env.stdout.flush()
      awaitShutdownSignal()
    } finally server.stop(0)
  }

  // $COVERAGE-OFF$
  // Only reachable via a real SIGINT/SIGTERM delivered to this process (exercised by
  // the acceptance suite's `stop` step, which sends the signal to the built jar).
  // Installing these handlers in-process during a unit test would override the whole
  // JVM's signal disposition for the rest of that test run, which is worse than the
  // gap it would close.
  private def awaitShutdownSignal(): Unit = {
    import sun.misc.{Signal, SignalHandler}
    val latch = new CountDownLatch(1)
    val handler = new SignalHandler {
      override def handle(signal: Signal): Unit = latch.countDown()
    }
    Signal.handle(new Signal("INT"), handler)
    Signal.handle(new Signal("TERM"), handler)
    latch.await()
  }
  // $COVERAGE-ON$
}
