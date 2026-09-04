package snap.repository

import snap.version.Version

/**
 * The complete repository value, SPEC.md §4.1. A `Repository` returned by
 * `RepositoryCodec.decode` has already passed §4.5's steps 1–4 (schema, patch
 * sort/one-per-dot, base closure and revision arithmetic, acyclic causality) via
 * `RepositoryValidator`. Steps 5–6 (validating each change against its materialized
 * base, and deterministic replay of the frontier) need the replay engine and are not
 * yet enforced here.
 */
final case class Repository(frontier: Version, patches: Vector[Patch])
