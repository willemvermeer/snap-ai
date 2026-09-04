package snap.workspace

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import snap.json.{JsonParser, JsonWriter}
import snap.replay.ReplayEngine
import snap.repository.{Repository, RepositoryCodec, RepositoryValidator}

/**
 * Reads and atomically writes `.snap/repository.json`. `snapDir` is the `.snap`
 * directory itself (as returned by [[RepoLocator.locate]]), not the repository root.
 */
object RepositoryFile {

  /**
   * SPEC.md §4.5's full validation pipeline: `RepositoryCodec.decode` covers steps
   * 1-4 (schema, sort/one-per-dot, base closure, acyclicity); replaying the frontier
   * here covers steps 5-6 (every change against its materialized base, deterministic
   * full replay) — the one place that always has both a patch's edit scripts and its
   * real materialized base, which schema-level decoding alone can't. Shared by every
   * source of repository JSON: the local file below and, via [[RepositoryOperand]], an
   * HTTP response body (SPEC.md §9: "parses the body as a repository value, and
   * validates it normally").
   */
  def decodeAndValidate(json: String): Repository = {
    val repository = RepositoryCodec.decode(JsonParser.parse(json))
    ReplayEngine.replay(RepositoryValidator.integrationOrder(repository.patches))
    repository
  }

  def read(snapDir: Path): Repository =
    decodeAndValidate(new String(Files.readAllBytes(snapDir.resolve("repository.json")), UTF_8))

  /**
   * SPEC.md §10: "replaces repository.json through a same-directory temporary file"
   * so a reader never observes a partially written file.
   */
  def write(snapDir: Path, repository: Repository): Unit = {
    val bytes = JsonWriter.write(RepositoryCodec.encode(repository)).getBytes(UTF_8)
    val target = snapDir.resolve("repository.json")
    val tmp = Files.createTempFile(snapDir, "repository", ".json.tmp")
    try {
      Files.write(tmp, bytes)
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch {
      case e: Throwable =>
        Files.deleteIfExists(tmp)
        throw e
    }
  }
}
