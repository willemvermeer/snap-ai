package snap.workspace

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import snap.json.{JsonParser, JsonWriter}
import snap.repository.{Repository, RepositoryCodec}

/**
 * Reads and atomically writes `.snap/repository.json`. `snapDir` is the `.snap`
 * directory itself (as returned by [[RepoLocator.locate]]), not the repository root.
 */
object RepositoryFile {

  def read(snapDir: Path): Repository =
    RepositoryCodec.decode(
      JsonParser.parse(new String(Files.readAllBytes(snapDir.resolve("repository.json")), UTF_8))
    )

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
