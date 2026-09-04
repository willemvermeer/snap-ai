package snap.replay

import snap.repository.{Patch, RepositoryValidator}
import snap.version.Version

/**
 * SPEC.md §4.1's "known" (or "materializable") version: "syntactically valid, every
 * patch (c, n) selected by n <= V[c] exists, and that selected set contains the
 * complete base of every selected patch." `diff` and `revert` reject a target version
 * unless it is known by this definition.
 */
object VersionResolution {

  /**
   * Resolves `version` against `patches`, returning the exact subset needed to
   * materialize it, in canonical integration order — or `None` if `version` is not
   * known in this patch set (a dot it references, directly or transitively through a
   * base, doesn't exist).
   */
  def resolve(version: Version, patches: Vector[Patch]): Option[Vector[Patch]] = {
    val byDot = patches.map(p => p.dot -> p).toMap
    var toVisit = version.sortedComponents.toSet
    var visited = Set.empty[(String, Long)]
    var known = true
    while (known && toVisit.nonEmpty) {
      val dot = toVisit.head
      toVisit -= dot
      if (!visited(dot)) {
        byDot.get(dot) match {
          case Some(patch) =>
            visited += dot
            toVisit ++= patch.base.sortedComponents
          case None =>
            known = false
        }
      }
    }
    if (!known) None
    else Some(RepositoryValidator.integrationOrder(patches.filter(p => visited(p.dot))))
  }
}
