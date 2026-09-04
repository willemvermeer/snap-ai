package snap.repository

import snap.version.Version

/**
 * One patch, SPEC.md §4.2: names its dot's revision, its exact causal base, and the
 * changes it makes. `revision = base[author] + 1` is enforced by the codec at decode
 * time, not here — this type carries the value, not the invariant proof.
 */
final case class Patch(
  author: String,
  revision: Long,
  base: Version,
  message: String,
  changes: Vector[Change]
) {
  def dot: (String, Long) = (author, revision)

  /**
   * SPEC.md §4.2: "result = B with result[author] = revision. All other result
   * components equal the base."
   */
  def resultVersion: Version = base.join(Version.fromPairs(Seq(author -> revision)))
}
