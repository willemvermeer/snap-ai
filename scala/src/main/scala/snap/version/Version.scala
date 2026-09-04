package snap.version

import snap.SnapError
import snap.id.ContributorId

/**
 * A vector clock: a map from contributor id to that contributor's latest revision
 * (SPEC.md §3). An absent contributor's revision is 0 ("no revision"), so a `Version`
 * never stores a zero component explicitly — that keeps structural equality on
 * `components` exactly match §3.3's "V = W iff every component is equal".
 */
final case class Version private (components: Map[String, Long]) {
  def revisionOf(contributor: String): Long = components.getOrElse(contributor, 0L)

  def isEmpty: Boolean = components.isEmpty

  /**
   * Sorted by contributor id in unsigned UTF-8 byte order. Every contributor id is
   * ASCII-only (`ContributorId.isValid` enforces this), so plain string ordering already
   * matches unsigned UTF-8 byte order — no separate byte-level comparator is needed.
   */
  def sortedComponents: Vector[(String, Long)] = components.toVector.sortBy(_._1)

  /**
   * SPEC.md §3.2's canonical syntax: `()`, or `(id->revision,...)` sorted by contributor,
   * no spaces.
   */
  def toCanonicalString: String =
    if (isEmpty) "()"
    else sortedComponents.map { case (id, rev) => s"$id->$rev" }.mkString("(", ",", ")")

  /**
   * SPEC.md §3.3's causal comparison, computed in one pass over the union of
   * contributors: `Before` iff every component here is <= the other's with at least one
   * strictly less; `After` is the converse; equal on every component is `Equal`;
   * otherwise the two are `Concurrent`.
   */
  def causalOrder(other: Version): CausalOrder = {
    val ids = components.keySet ++ other.components.keySet
    var anyLess = false
    var anyGreater = false
    ids.foreach { id =>
      val here = revisionOf(id)
      val there = other.revisionOf(id)
      if (here < there) anyLess = true
      if (here > there) anyGreater = true
    }
    (anyLess, anyGreater) match {
      case (false, false) => CausalOrder.Equal
      case (true, false) => CausalOrder.Before
      case (false, true) => CausalOrder.After
      case (true, true) => CausalOrder.Concurrent
    }
  }

  def isBefore(other: Version): Boolean = causalOrder(other) == CausalOrder.Before
  def isAfter(other: Version): Boolean = causalOrder(other) == CausalOrder.After
  def isConcurrentWith(other: Version): Boolean = causalOrder(other) == CausalOrder.Concurrent

  /** SPEC.md §3.3: `join(V, W)[c] = max(V[c], W[c])`. */
  def join(other: Version): Version = {
    val ids = components.keySet ++ other.components.keySet
    new Version(ids.iterator.map(id => id -> math.max(revisionOf(id), other.revisionOf(id))).toMap)
  }
}

sealed trait CausalOrder
object CausalOrder {
  case object Equal extends CausalOrder
  case object Before extends CausalOrder
  case object After extends CausalOrder
  case object Concurrent extends CausalOrder
}

object Version {

  /** JavaScript's `Number.MAX_SAFE_INTEGER`, SPEC.md §3.1's revision ceiling. */
  val MaxRevision: Long = 9007199254740991L

  val Empty: Version = new Version(Map.empty)

  /**
   * Builds a Version from (contributor, revision) pairs, e.g. a repository.json array's
   * contents — see SPEC.md §3.2's JSON form. Input order is irrelevant (a `Version`'s
   * identity is its component map, not any sequence), but every pair must be
   * independently valid and no contributor may repeat. This does not enforce that the
   * pairs arrived pre-sorted; array-ordering strictness for on-disk JSON, if any, is a
   * repository-schema concern, not this pure algebra module's.
   */
  def fromPairs(pairs: Seq[(String, Long)]): Version = {
    var seen = Map.empty[String, Long]
    pairs.foreach { case (id, revision) =>
      if (!ContributorId.isValid(id)) throw SnapError(s"invalid contributor id: $id")
      if (revision <= 0 || revision > MaxRevision) throw SnapError(s"invalid revision: $revision")
      if (seen.contains(id)) throw SnapError(s"duplicate contributor in version: $id")
      seen += id -> revision
    }
    new Version(seen)
  }

  /**
   * Parses SPEC.md §3.2's canonical CLI syntax exactly: `()`, or
   * `(id->revision,...)` with contributors sorted by unsigned UTF-8 bytes, no spaces, no
   * duplicate ids, no explicit or leading zero, and revisions within the JS safe-integer
   * range. Any deviation — including a semantically equivalent but differently ordered
   * or spaced string — is rejected; this is not a lenient parser, matching the spec's
   * "Duplicate IDs, explicit zeroes, leading zeroes, overflow, invalid IDs, whitespace,
   * and noncanonical ordering are errors."
   */
  def parseCanonical(text: String): Version =
    if (text == "()") {
      Empty
    } else {
      def invalid(): Nothing = throw SnapError(s"invalid version: $text")
      if (text.length < 2 || text.head != '(' || text.last != ')') invalid()
      // `body` can only be empty when `text` is exactly "()", already handled above, so
      // there's no separate empty-body case to guard here.
      val body = text.substring(1, text.length - 1)

      val pairs = body.split(",", -1).toVector.map { part =>
        val arrow = part.indexOf("->")
        if (arrow <= 0) invalid()
        val id = part.substring(0, arrow)
        val revisionText = part.substring(arrow + 2)
        if (!ContributorId.isValid(id)) invalid()
        if (revisionText.isEmpty || !revisionText.forall(_.isDigit)) invalid()
        if (revisionText == "0") invalid() // explicit zero
        if (revisionText.length > 1 && revisionText.head == '0') invalid() // leading zero
        // revisionText is validated all-digit above, so BigInt parsing here cannot fail.
        val revision = BigInt(revisionText)
        if (revision > MaxRevision) invalid() // overflow
        id -> revision.toLong
      }

      val ids = pairs.map(_._1)
      if (ids.distinct.length != ids.length) invalid() // duplicate ids
      if (ids != ids.sorted) invalid() // noncanonical ordering

      new Version(pairs.toMap)
    }

  /**
   * SPEC.md §3.4's Snap order: sort the union of contributor ids, then compare revisions
   * at each in that order; the first unequal one decides. A total order that extends
   * causal order (if `x` causally precedes `y`, every shared/added component only grows,
   * so the first differing sorted component can only favor `y`) but is otherwise
   * arbitrary for concurrent versions.
   */
  val snapOrdering: Ordering[Version] = (x: Version, y: Version) => {
    val ids = (x.components.keySet ++ y.components.keySet).toVector.sorted
    ids.iterator
      .map(id => java.lang.Long.compare(x.revisionOf(id), y.revisionOf(id)))
      .find(_ != 0)
      .getOrElse(0)
  }
}
