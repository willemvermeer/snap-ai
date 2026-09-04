package snap.repository

import snap.SnapError
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

object Patch {

  /**
   * SPEC.md §4.2: "message is a nonempty UTF-8 string. It may contain tab and LF but no
   * other ASCII control character." The underlying rule is shared by `RepositoryCodec`
   * (reading a historical patch) and `snap commit` (authoring a new one), but each
   * reports it in its own words — `commit` fails with "invalid commit message"
   * regardless of which part of this predicate a hand-typed message violates, while
   * reading an existing repository.json is more diagnostic. The 4096-byte cap §7.5
   * additionally imposes on `commit` is not part of this shared rule, since a generated
   * `revert` message may legitimately exceed it.
   */
  def isValidMessage(message: String): Boolean =
    message.nonEmpty && !message.exists(c =>
      (c.toInt < 0x20 || c.toInt == 0x7f) && c != '\t' && c != '\n'
    )

  def validateMessage(message: String): Unit = {
    if (message.isEmpty) throw SnapError("patch message is empty")
    if (message.exists(c => (c.toInt < 0x20 || c.toInt == 0x7f) && c != '\t' && c != '\n')) {
      throw SnapError("patch message contains a disallowed control character")
    }
  }
}
