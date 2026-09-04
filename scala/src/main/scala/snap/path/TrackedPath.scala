package snap.path

import java.nio.charset.StandardCharsets.UTF_8

/**
 * Tracked-path rules from SPEC.md §2: a UTF-8 relative path using `/` separators. It
 * must be nonempty, contain no ASCII control character or backslash, contain no empty,
 * `.`, or `..` segment, and have no first segment equal to `.snap`. Snap performs no
 * Unicode or case normalization.
 */
object TrackedPath {
  def isValid(path: String): Boolean =
    path.nonEmpty &&
      !path.exists(c => c.toInt < 0x20 || c.toInt == 0x7f || c == '\\') && {
        val segments = path.split("/", -1).toVector
        segments.nonEmpty &&
        !segments.exists(s => s.isEmpty || s == "." || s == "..") &&
        segments.head != ".snap"
      }

  /**
   * SPEC.md §2: "Paths sort by unsigned lexicographic UTF-8 bytes." Java/Scala's default
   * `String` ordering compares UTF-16 code units, which disagrees with unsigned UTF-8
   * byte order for supplementary-plane characters (encoded as surrogate pairs, whose
   * code unit values are lower than the BMP's own upper range despite representing
   * higher code points) — so paths need their own comparator rather than reusing
   * `Ordering.String`, unlike contributor ids (§3.1), which are constrained to ASCII and
   * so have no such discrepancy.
   */
  val ordering: Ordering[String] = (a: String, b: String) => {
    val ab = a.getBytes(UTF_8)
    val bb = b.getBytes(UTF_8)
    val len = math.min(ab.length, bb.length)
    var i = 0
    var result = 0
    while (result == 0 && i < len) {
      result = Integer.compare(ab(i) & 0xff, bb(i) & 0xff)
      i += 1
    }
    if (result != 0) result else Integer.compare(ab.length, bb.length)
  }
}
