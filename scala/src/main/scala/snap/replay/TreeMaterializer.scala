package snap.replay

import snap.repository.{Patch, Repository, RepositoryValidator}

/**
 * Materializes a repository's (or an arbitrary known version's) tree, delegating to
 * [[ReplayEngine]] for the actual §6 algorithm. Kept as a thin, warning-discarding
 * convenience for the callers that only need the tree (`status`, `commit`, `diff`,
 * `revert`) — `merge` (plan unit 8) calls `ReplayEngine.replay` directly since it needs
 * the warnings too.
 */
object TreeMaterializer {

  def materialize(repository: Repository): Map[String, Vector[Byte]] =
    materializeOrdered(RepositoryValidator.integrationOrder(repository.patches))

  /**
   * Integrates patches already in canonical order (e.g. from
   * [[VersionResolution.resolve]]) — the reusable half of `materialize`, for callers
   * materializing an arbitrary known version rather than the whole repository's
   * frontier.
   */
  def materializeOrdered(orderedPatches: Vector[Patch]): Map[String, Vector[Byte]] =
    ReplayEngine.replay(orderedPatches).tree
}
