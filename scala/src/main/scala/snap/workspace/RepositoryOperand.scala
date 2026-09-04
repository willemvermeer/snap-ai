package snap.workspace

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import snap.SnapError
import snap.repository.Repository

/**
 * SPEC.md §7: "A repository operand is an explicit `http://` or `https://` URL, or
 * otherwise a local path to a repository root" — the root itself (containing `.snap`
 * directly), not resolved via [[RepoLocator]]'s walk-up search.
 *
 * SPEC.md §9: an HTTP operand performs exactly "one GET of that exact URL, requires
 * status 200, parses the body as a repository value, and validates it normally." No
 * redirects, auth, or caching — a default [[HttpClient]]'s redirect policy is already
 * `NEVER`, so a redirect response simply arrives as its own (non-200) status.
 */
object RepositoryOperand {

  private val client = HttpClient.newHttpClient()

  def resolve(operand: String, cwd: Path): Repository =
    if (operand.startsWith("http://") || operand.startsWith("https://")) {
      resolveHttp(operand)
    } else {
      val snapDir = cwd.resolve(operand).resolve(".snap")
      if (!Files.isDirectory(snapDir)) throw SnapError(s"not a Snap repository: $operand")
      RepositoryFile.read(snapDir)
    }

  private def resolveHttp(url: String): Repository = {
    val response =
      try {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
      } catch {
        case e: Exception => throw SnapError(s"HTTP request failed: ${e.getMessage}")
      }
    if (response.statusCode() != 200) throw SnapError(s"HTTP ${response.statusCode()}")
    RepositoryFile.decodeAndValidate(response.body())
  }
}
