package snap.repo

import java.nio.file.{Files, Path}

/**
 * Locates the nearest repository by walking from a starting directory to the filesystem
 * root, per SPEC.md §7: "Snap locates the nearest repository by walking from the current
 * directory to the filesystem root."
 */
object RepoLocator {

  /** Returns the `.snap` directory of the nearest enclosing repository, if any. */
  def locate(start: Path): Option[Path] = {
    var dir: Path = start.toAbsolutePath.normalize()
    var result: Option[Path] = None
    var continue = true
    while (continue) {
      val snapDir = dir.resolve(".snap")
      if (Files.isDirectory(snapDir)) {
        result = Some(snapDir)
        continue = false
      } else {
        val parent = dir.getParent
        if (parent == null) {
          continue = false
        } else {
          dir = parent
        }
      }
    }
    result
  }
}
