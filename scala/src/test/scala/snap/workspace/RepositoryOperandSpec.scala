package snap.workspace

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.SnapError
import snap.repository.Repository
import snap.version.Version

/**
 * SPEC.md §9's HTTP repository operand: "one GET of that exact URL, requires status
 * 200, parses the body as a repository value, and validates it normally." Local-path
 * resolution is covered indirectly by `diff --repo`/`merge`'s own CLI specs.
 */
class RepositoryOperandSpec extends AnyFunSuite with Matchers {

  private val validJson = """{"format":1,"frontier":[],"patches":[]}"""

  private def withServer(status: Int, body: String)(test: String => Unit): Unit = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/repository.json",
      exchange => {
        val bytes = body.getBytes(UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        val out = exchange.getResponseBody
        try out.write(bytes)
        finally out.close()
        exchange.close()
      }
    )
    server.setExecutor(null)
    server.start()
    try test(s"http://127.0.0.1:${server.getAddress.getPort}/repository.json")
    finally server.stop(0)
  }

  test("resolves a valid repository over HTTP with one GET") {
    withServer(200, validJson) { url =>
      RepositoryOperand.resolve(url, Path.of(".")) shouldBe Repository(Version.Empty, Vector.empty)
    }
  }

  test("a non-200 status is reported as HTTP <status>, not parsed as a body") {
    withServer(404, "irrelevant body") { url =>
      val ex = intercept[SnapError](RepositoryOperand.resolve(url, Path.of(".")))
      ex.getMessage shouldBe "HTTP 404"
    }
  }

  test("a malformed body surfaces the same invalid JSON error as a local file") {
    withServer(200, "not-json") { url =>
      val ex = intercept[SnapError](RepositoryOperand.resolve(url, Path.of(".")))
      ex.getMessage should include("invalid JSON")
    }
  }

  test("a schema-invalid but well-formed body fails the same validation as a local file") {
    withServer(200, """{"format":1,"frontier":[],"patches":[],"bad":true}""") { url =>
      intercept[SnapError](RepositoryOperand.resolve(url, Path.of(".")))
    }
  }

  test("a connection failure is reported as a SnapError, not an uncaught exception") {
    val ex =
      intercept[SnapError](
        RepositoryOperand.resolve("http://127.0.0.1:1/repository.json", Path.of("."))
      )
    ex.getMessage should include("HTTP request failed")
  }

  test("a local path operand that is not a Snap repository is rejected") {
    val notARepo = Files.createTempDirectory("snap-not-a-repo-")
    val ex = intercept[SnapError](RepositoryOperand.resolve(notARepo.toString, Path.of(".")))
    ex.getMessage shouldBe s"not a Snap repository: ${notARepo.toString}"
  }
}
