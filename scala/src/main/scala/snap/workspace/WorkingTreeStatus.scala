package snap.workspace

import snap.path.TrackedPath

/**
 * SPEC.md §7.3's status codes: `A` for absent-to-present, `M` for changed bytes, `D`
 * for present-to-absent.
 */
sealed trait PathStatus
object PathStatus {
  case object Added extends PathStatus
  case object Modified extends PathStatus
  case object Deleted extends PathStatus
}

/**
 * Compares the current (materialized) tree against the working tree, per §2: "The
 * working tree is clean when its path/byte map exactly equals the current tree." The
 * result is every differing path, sorted (§7.3: "sorted by path").
 */
object WorkingTreeStatus {
  def compare(
    current: Map[String, Vector[Byte]],
    working: Map[String, Vector[Byte]]
  ): Vector[(String, PathStatus)] = {
    val paths = (current.keySet ++ working.keySet).toVector.sorted(TrackedPath.ordering)
    paths.flatMap { path =>
      (current.get(path), working.get(path)) match {
        case (None, Some(_)) => Some(path -> PathStatus.Added)
        case (Some(_), None) => Some(path -> PathStatus.Deleted)
        case (Some(a), Some(b)) if a != b => Some(path -> PathStatus.Modified)
        case _ => None
      }
    }
  }
}
