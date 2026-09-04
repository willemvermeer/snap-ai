package snap.version

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.SnapError

class VersionSpec extends AnyFunSuite with Matchers {

  private def v(pairs: (String, Long)*): Version = Version.fromPairs(pairs)

  test("the empty version's canonical string is ()") {
    Version.Empty.toCanonicalString shouldBe "()"
    Version.Empty.isEmpty shouldBe true
  }

  test("canonical string sorts contributors by unsigned byte order with no spaces") {
    v("vigoo@example.com" -> 239L, "jdegoes@example.com" -> 2323L).toCanonicalString shouldBe
      "(jdegoes@example.com->2323,vigoo@example.com->239)"
  }

  test("parseCanonical round-trips a canonical string") {
    val text = "(jdegoes@example.com->2323,vigoo@example.com->239)"
    Version.parseCanonical(text).toCanonicalString shouldBe text
  }

  test("parseCanonical accepts the empty version") {
    Version.parseCanonical("()") shouldBe Version.Empty
  }

  test("parseCanonical accepts a single-component version") {
    Version.parseCanonical("(a@x->1)") shouldBe v("a@x" -> 1L)
  }

  test("parseCanonical rejects missing or malformed parentheses") {
    a[SnapError] should be thrownBy Version.parseCanonical("a@x->1")
    a[SnapError] should be thrownBy Version.parseCanonical("(a@x->1")
    a[SnapError] should be thrownBy Version.parseCanonical("a@x->1)")
    a[SnapError] should be thrownBy Version.parseCanonical("(")
    a[SnapError] should be thrownBy Version.parseCanonical("")
  }

  test("parseCanonical rejects an empty non-() body") {
    a[SnapError] should be thrownBy Version.parseCanonical("(,)")
  }

  test("parseCanonical rejects explicit and leading zero revisions") {
    a[SnapError] should be thrownBy Version.parseCanonical("(a@x->0)")
    a[SnapError] should be thrownBy Version.parseCanonical("(a@x->01)")
    a[SnapError] should be thrownBy Version.parseCanonical("(a@x->007)")
  }

  test("parseCanonical rejects duplicate contributors") {
    a[SnapError] should be thrownBy Version.parseCanonical("(a@x->1,a@x->2)")
  }

  test("parseCanonical rejects noncanonical (unsorted) ordering") {
    a[SnapError] should be thrownBy Version.parseCanonical("(b@x->1,a@x->1)")
  }

  test("parseCanonical rejects whitespace anywhere") {
    a[SnapError] should be thrownBy Version.parseCanonical(" (a@x->1)")
    a[SnapError] should be thrownBy Version.parseCanonical("(a@x->1) ")
    a[SnapError] should be thrownBy Version.parseCanonical("(a@x -> 1)")
    a[SnapError] should be thrownBy Version.parseCanonical("(a@x->1, b@x->1)")
  }

  test("parseCanonical rejects invalid contributor ids") {
    a[SnapError] should be thrownBy Version.parseCanonical("(not-an-id->1)")
  }

  test("parseCanonical rejects revision overflow beyond the JS safe integer") {
    a[SnapError] should be thrownBy Version.parseCanonical(s"(a@x->${Version.MaxRevision + 1})")
    Version.parseCanonical(s"(a@x->${Version.MaxRevision})") shouldBe v(
      "a@x" -> Version.MaxRevision
    )
  }

  test("parseCanonical rejects a non-digit revision") {
    a[SnapError] should be thrownBy Version.parseCanonical("(a@x->1a)")
    a[SnapError] should be thrownBy Version.parseCanonical("(a@x->)")
  }

  test("fromPairs rejects invalid ids, non-positive or overflowing revisions, and duplicates") {
    a[SnapError] should be thrownBy Version.fromPairs(Seq("not-an-id" -> 1L))
    a[SnapError] should be thrownBy Version.fromPairs(Seq("a@x" -> 0L))
    a[SnapError] should be thrownBy Version.fromPairs(Seq("a@x" -> -1L))
    a[SnapError] should be thrownBy Version.fromPairs(Seq("a@x" -> (Version.MaxRevision + 1)))
    a[SnapError] should be thrownBy Version.fromPairs(Seq("a@x" -> 1L, "a@x" -> 2L))
  }

  test("fromPairs ignores input order") {
    v("a@x" -> 1L, "b@x" -> 1L) shouldBe v("b@x" -> 1L, "a@x" -> 1L)
  }

  test("equal versions compare Equal") {
    Version.Empty.causalOrder(Version.Empty) shouldBe CausalOrder.Equal
    v("a@x" -> 1L).causalOrder(v("a@x" -> 1L)) shouldBe CausalOrder.Equal
  }

  test("a strict componentwise increase is Before/After") {
    val older = v("a@x" -> 1L)
    val newer = v("a@x" -> 2L)
    older.causalOrder(newer) shouldBe CausalOrder.Before
    newer.causalOrder(older) shouldBe CausalOrder.After
    older.isBefore(newer) shouldBe true
    newer.isAfter(older) shouldBe true
  }

  test("an absent contributor counts as revision zero for comparison") {
    Version.Empty.causalOrder(v("a@x" -> 1L)) shouldBe CausalOrder.Before
  }

  test("divergent single-contributor changes are concurrent") {
    val b = v("a@x" -> 2L, "b@x" -> 1L)
    val c = v("a@x" -> 1L, "b@x" -> 2L)
    b.causalOrder(c) shouldBe CausalOrder.Concurrent
    c.causalOrder(b) shouldBe CausalOrder.Concurrent
    b.isConcurrentWith(c) shouldBe true
  }

  test("concurrency is not equivalent to before or after") {
    val a = v("a@x" -> 2L, "b@x" -> 1L)
    val b = v("a@x" -> 1L, "b@x" -> 2L)
    a.causalOrder(b) shouldBe CausalOrder.Concurrent
    a should not be b
  }

  test("join takes the componentwise max") {
    val a = v("a@x" -> 2L, "b@x" -> 1L)
    val b = v("a@x" -> 1L, "b@x" -> 2L)
    a.join(b) shouldBe v("a@x" -> 2L, "b@x" -> 2L)
  }

  test("join is commutative, associative, and idempotent") {
    val a = v("a@x" -> 2L, "b@x" -> 1L)
    val b = v("a@x" -> 1L, "b@x" -> 3L, "c@x" -> 1L)
    val c = v("b@x" -> 5L, "c@x" -> 2L)

    a.join(b) shouldBe b.join(a)
    a.join(b).join(c) shouldBe a.join(b.join(c))
    a.join(a) shouldBe a
  }

  test("join with the empty version is the identity") {
    val a = v("a@x" -> 3L, "b@x" -> 1L)
    a.join(Version.Empty) shouldBe a
    Version.Empty.join(a) shouldBe a
  }

  test("Snap order extends causal order") {
    val older = v("a@x" -> 1L)
    val newer = v("a@x" -> 2L)
    Version.snapOrdering.compare(older, newer) should be < 0
    Version.snapOrdering.compare(newer, older) should be > 0
    Version.snapOrdering.compare(older, older) shouldBe 0
  }

  test("Snap order is a deterministic total order for concurrent versions") {
    val b = v("a@x" -> 2L, "b@x" -> 1L)
    val c = v("a@x" -> 1L, "b@x" -> 2L)
    b.causalOrder(c) shouldBe CausalOrder.Concurrent
    // "a@x" sorts before "b@x", and b's a@x component (2) is greater than c's (1).
    Version.snapOrdering.compare(b, c) should be > 0
    Version.snapOrdering.compare(c, b) should be < 0
  }

  test("Snap order treats an absent component as zero") {
    Version.snapOrdering.compare(Version.Empty, v("a@x" -> 1L)) should be < 0
  }
}
