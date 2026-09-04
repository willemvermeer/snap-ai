package snap.workspace

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters._
import snap.SnapError

/**
 * Reads the working tree's tracked path/byte map, per SPEC.md §2: every regular file
 * below the repository root except `.snap` and its contents; symlinks and other
 * non-regular entries are unsupported and must be reported, never followed or silently
 * skipped (§10). Directories are implicit and never appear in the result.
 */
object WorkingTree {

  /**
   * `root` is the repository root (the directory containing `.snap`), not `.snap`
   * itself.
   */
  def scan(root: Path): Map[String, Vector[Byte]] = {
    var result = Map.empty[String, Vector[Byte]]

    def walk(dir: Path, relPrefix: String): Unit = {
      val children = Files.list(dir).iterator().asScala.toVector.sortBy(_.getFileName.toString)
      children.foreach { child =>
        val name = child.getFileName.toString
        if (relPrefix.isEmpty && name == ".snap") {
          // Never tracked, never scanned into.
        } else {
          val relPath = if (relPrefix.isEmpty) name else s"$relPrefix/$name"
          // NOFOLLOW_LINKS: a symlink must be reported as unsupported even if it's
          // dangling, rather than resolved (and possibly failing to resolve at all).
          val attrs =
            Files.readAttributes(child, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          if (attrs.isRegularFile) {
            result += relPath -> Files.readAllBytes(child).toVector
          } else if (attrs.isDirectory) {
            walk(child, relPath)
          } else {
            throw SnapError(s"unsupported working tree entry: $relPath")
          }
        }
      }
    }

    walk(root, "")
    result
  }
}
