package snap.id

import snap.SnapError

/**
 * Contributor ID grammar, SPEC.md §3.1: an ASCII email-shaped string with exactly one
 * '@' and nonempty text on both sides, no control character, whitespace, ',', '(', ')',
 * or substring "->", at most 254 bytes. Snap preserves spelling exactly (no normalization).
 */
object ContributorId {
  private val MaxBytes = 254

  def isValid(raw: String): Boolean =
    raw.nonEmpty &&
      raw.forall(_.toInt < 128) &&
      raw.getBytes("UTF-8").length <= MaxBytes &&
      raw.count(_ == '@') == 1 &&
      hasNonemptySides(raw) &&
      !raw.exists(c => c.toInt < 0x20 || c.toInt == 0x7f) &&
      !raw.exists(Character.isWhitespace(_: Char)) &&
      !raw.contains(',') &&
      !raw.contains('(') &&
      !raw.contains(')') &&
      !raw.contains("->")

  private def hasNonemptySides(raw: String): Boolean = {
    val atIndex = raw.indexOf('@')
    atIndex > 0 && atIndex < raw.length - 1
  }

  def require(raw: String): String =
    if (isValid(raw)) raw else throw SnapError(s"invalid contributor id: $raw")
}
