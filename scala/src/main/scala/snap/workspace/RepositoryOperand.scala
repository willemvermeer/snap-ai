package snap.workspace

import java.nio.file.{Files, Path}
import snap.SnapError
import snap.repository.Repository

/**
 * SPEC.md §7: "A repository operand is an explicit `http://` or `https://` URL, or
 * otherwise a local path to a repository root" — the root itself (containing `.snap`
 * directly), not resolved via [[RepoLocator]]'s walk-up search.
 *
 * Only local paths are implemented so far; resolving an `http://`/`https://` operand is
 * plan unit 9's HTTP client.
 */
object RepositoryOperand {
  def resolve(operand: String, cwd: Path): Repository =
    if (operand.startsWith("http://") || operand.startsWith("https://")) {
      throw SnapError(s"HTTP repositories are not yet supported: $operand")
    } else {
      val snapDir = cwd.resolve(operand).resolve(".snap")
      if (!Files.isDirectory(snapDir)) throw SnapError(s"not a Snap repository: $operand")
      RepositoryFile.read(snapDir)
    }
}
