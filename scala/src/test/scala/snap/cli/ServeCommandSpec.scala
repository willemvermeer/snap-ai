package snap.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.SnapError

/**
 * SPEC.md §7.9/§9's `snap --serve` HTTP surface, tested directly against
 * [[ServeCommand.bind]] rather than [[ServeCommand.run]] — `run` blocks on a real
 * SIGINT/SIGTERM (see its own `$COVERAGE-OFF$` note), which the acceptance suite's
 * `start`/`stop` steps exercise against the built jar instead.
 */
class ServeCommandSpec extends AnyFunSuite with Matchers {

  private val client = HttpClient.newHttpClient()

  private def get(url: String, method: String = "GET"): HttpResponse[String] =
    client.send(
      HttpRequest
        .newBuilder(URI.create(url))
        .method(method, HttpRequest.BodyPublishers.noBody())
        .build(),
      HttpResponse.BodyHandlers.ofString(UTF_8)
    )

  test("parsePort defaults to 8765 with no argument") {
    ServeCommand.parsePort(Vector.empty) shouldBe 8765
  }

  test("parsePort accepts an explicit port, including 0 for an OS-assigned one") {
    ServeCommand.parsePort(Vector("0")) shouldBe 0
    ServeCommand.parsePort(Vector("65535")) shouldBe 65535
  }

  test("parsePort rejects an out-of-range or non-numeric port") {
    intercept[SnapError](ServeCommand.parsePort(Vector("65536"))).getMessage shouldBe
      "invalid port: 65536"
    intercept[SnapError](
      ServeCommand.parsePort(Vector("-1"))
    ).getMessage shouldBe "invalid port: -1"
    intercept[SnapError](
      ServeCommand.parsePort(Vector("abc"))
    ).getMessage shouldBe "invalid port: abc"
  }

  test("parsePort rejects more than one operand") {
    intercept[SnapError](ServeCommand.parsePort(Vector("1", "2"))).getMessage shouldBe
      "invalid command or arguments"
  }

  test("GET /repository.json returns the snapshot with the exact content type") {
    val server = ServeCommand.bind(0, "snapshot-bytes".getBytes(UTF_8))
    try {
      val url = s"http://127.0.0.1:${server.getAddress.getPort}/repository.json"
      val response = get(url)
      response.statusCode() shouldBe 200
      response
        .headers()
        .firstValue("content-type")
        .orElse("") shouldBe "application/json; charset=utf-8"
      response.body() shouldBe "snapshot-bytes"
    } finally server.stop(0)
  }

  test("HEAD /repository.json returns the same status and headers with no body") {
    val server = ServeCommand.bind(0, "snapshot-bytes".getBytes(UTF_8))
    try {
      val url = s"http://127.0.0.1:${server.getAddress.getPort}/repository.json"
      val response = get(url, "HEAD")
      response.statusCode() shouldBe 200
      response
        .headers()
        .firstValue("content-type")
        .orElse("") shouldBe "application/json; charset=utf-8"
      response.body() shouldBe ""
    } finally server.stop(0)
  }

  test("an unknown path is 404") {
    val server = ServeCommand.bind(0, "snapshot-bytes".getBytes(UTF_8))
    try {
      val url = s"http://127.0.0.1:${server.getAddress.getPort}/other"
      get(url).statusCode() shouldBe 404
    } finally server.stop(0)
  }

  test("a query string on the resource path is 404, since it isn't the exact resource") {
    val server = ServeCommand.bind(0, "snapshot-bytes".getBytes(UTF_8))
    try {
      val url = s"http://127.0.0.1:${server.getAddress.getPort}/repository.json?x=1"
      get(url).statusCode() shouldBe 404
    } finally server.stop(0)
  }

  test("an unsupported method on the resource path is 405 with an Allow header") {
    val server = ServeCommand.bind(0, "snapshot-bytes".getBytes(UTF_8))
    try {
      val url = s"http://127.0.0.1:${server.getAddress.getPort}/repository.json"
      val response = get(url, "POST")
      response.statusCode() shouldBe 405
      response.headers().firstValue("allow").orElse("") shouldBe "GET, HEAD"
    } finally server.stop(0)
  }

  // `run`'s own body is entirely `$COVERAGE-OFF$` (it blocks on a real OS signal once
  // the server is up), but its fast-failing validation still runs safely here, since
  // neither case reaches `bind`/`awaitShutdownSignal`.
  private def env(cwd: java.nio.file.Path): Cli.Env =
    Cli.Env(
      Map.empty,
      cwd,
      new PrintStream(new ByteArrayOutputStream()),
      new PrintStream(
        new ByteArrayOutputStream()
      ),
      false,
      false
    )

  test("run rejects an invalid port before touching the repository") {
    val cwd = Files.createTempDirectory("snap-serve-")
    intercept[SnapError](ServeCommand.run(Vector("70000"), env(cwd))).getMessage shouldBe
      "invalid port: 70000"
  }

  test("run requires a Snap repository") {
    val cwd = Files.createTempDirectory("snap-serve-")
    intercept[SnapError](ServeCommand.run(Vector("0"), env(cwd))).getMessage shouldBe
      "not a Snap repository"
  }
}
