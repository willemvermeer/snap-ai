package snap.repository

import snap.SnapError
import snap.version.Version

/**
 * SPEC.md §4.5's steps 2-4: patch sort order and one-value-per-dot, complete base
 * closure (with "no unreachable patches" from §4.1), and acyclic causality. Steps 5-6
 * (replay-dependent — a change against its materialized base, full frontier replay)
 * need the replay engine and are not enforced here.
 */
object RepositoryValidator {

  def validate(frontier: Version, patches: Vector[Patch]): Repository = {
    checkSortedByAuthorThenRevision(patches)
    val byDot = buildDotIndex(patches)
    checkUnreachable(byDot, closureFrom(frontier, byDot))
    integrationOrder(patches) // run for its acyclicity check; the order itself isn't needed here
    Repository(frontier, patches)
  }

  private def checkSortedByAuthorThenRevision(patches: Vector[Patch]): Unit = {
    val dots = patches.map(_.dot)
    if (dots != dots.sorted) throw SnapError("patches are not sorted by author and revision")
  }

  private def buildDotIndex(patches: Vector[Patch]): Map[(String, Long), Patch] = {
    var index = Map.empty[(String, Long), Patch]
    patches.foreach { p =>
      if (index.contains(p.dot))
        throw SnapError(s"duplicate patch dot: ${p.author} revision ${p.revision}")
      index += p.dot -> p
    }
    index
  }

  /**
   * BFS over base references starting from `frontier`. Gracefully handles a cycle (a
   * dot already visited is just skipped) rather than looping forever — cycle rejection
   * is `integrationOrder`'s job, this only establishes which dots are reachable at all.
   */
  private def closureFrom(
    frontier: Version,
    byDot: Map[(String, Long), Patch]
  ): Set[(String, Long)] = {
    var toVisit = frontier.sortedComponents.toSet
    var visited = Set.empty[(String, Long)]
    while (toVisit.nonEmpty) {
      val dot = toVisit.head
      toVisit -= dot
      if (!visited(dot)) {
        val patch = byDot.getOrElse(dot, throw SnapError(s"missing ${dot._1} revision ${dot._2}"))
        visited += dot
        toVisit ++= patch.base.sortedComponents
      }
    }
    visited
  }

  private def checkUnreachable(
    byDot: Map[(String, Long), Patch],
    reachable: Set[(String, Long)]
  ): Unit = {
    val unreachable = (byDot.keySet -- reachable).toVector.sorted
    unreachable.headOption.foreach { case (author, revision) =>
      throw SnapError(s"unreachable patch: $author revision $revision")
    }
  }

  /**
   * SPEC.md §6.1's patch *selection* order only (not §6.2's integration, which needs
   * file content and OT) — repeatedly take the least ready patch, by Snap order of its
   * result version then author then revision, where "ready" means every dot its base
   * references has already been integrated. A patch set that can't be fully drained
   * this way is exactly SPEC.md's "the history has a cycle or missing dependency"
   * (quoted verbatim in the error below, right after §4.5's numbered steps) — since
   * `RepositoryValidator.validate` already confirmed every referenced dot exists
   * (`closureFrom`) and that the patch set has no extras (`checkUnreachable`), getting
   * stuck here specifically means a cycle.
   *
   * This selection order is exactly SPEC.md §6.1's canonical integration order, so the
   * replay engine (plan unit 7) can reuse it directly instead of re-deriving it.
   */
  def integrationOrder(patches: Vector[Patch]): Vector[Patch] = {
    val ordering: Ordering[Patch] =
      Ordering
        .by[Patch, Version](_.resultVersion)(Version.snapOrdering)
        .orElseBy(_.author)
        .orElseBy(_.revision)

    val result = Vector.newBuilder[Patch]
    var integrated = Set.empty[(String, Long)]
    var remaining = patches
    while (remaining.nonEmpty) {
      val ready = remaining.filter(_.base.sortedComponents.forall(integrated))
      if (ready.isEmpty) throw SnapError("cyclic or incomplete patch history")
      val next = ready.min(ordering)
      result += next
      integrated += next.dot
      remaining = remaining.filterNot(_ == next)
    }
    result.result()
  }
}
