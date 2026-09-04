package snap.workspace

import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters._

/**
 * Makes the working tree under `root` exactly match `target`, given that it currently
 * matches `current`. Used by `revert` (and, later, `merge`) — SPEC.md's replay-adjacent
 * installation description (§6.2's tail): "removes files that block required
 * directories, creates required directories, writes target files, and removes newly
 * empty directories so the filesystem represents exactly that target path/byte map."
 */
object TreeInstaller {
  def install(
    root: Path,
    current: Map[String, Vector[Byte]],
    target: Map[String, Vector[Byte]]
  ): Unit = {
    (current.keySet -- target.keySet).foreach(path => Files.deleteIfExists(root.resolve(path)))
    pruneEmptyDirectoriesUnder(root)
    target.foreach { case (path, bytes) =>
      if (!current.get(path).contains(bytes)) {
        val filePath = root.resolve(path)
        Files.createDirectories(filePath.getParent)
        Files.write(filePath, bytes.toArray)
      }
    }
  }

  /**
   * Prunes empty directories below `root`, skipping `.snap` — needed so, e.g., a path
   * that used to be a directory (now emptied by the deletions above) can become a
   * plain file instead.
   */
  private def pruneEmptyDirectoriesUnder(root: Path): Unit = {
    val topLevel = Files.list(root).iterator().asScala.toVector
    topLevel.foreach { child =>
      if (
        child.getFileName.toString != ".snap" && isRealDirectory(child) && isEmptyAfterPruning(
          child
        )
      ) {
        Files.delete(child)
      }
    }
  }

  /**
   * Recursively prunes empty subdirectories of `dir`, returning whether `dir` itself
   * ends up with nothing left in it.
   */
  private def isEmptyAfterPruning(dir: Path): Boolean = {
    val children = Files.list(dir).iterator().asScala.toVector
    children.foreach { child =>
      if (isRealDirectory(child) && isEmptyAfterPruning(child)) Files.delete(child)
    }
    Files.list(dir).iterator().asScala.isEmpty
  }

  private def isRealDirectory(path: Path): Boolean =
    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
}
