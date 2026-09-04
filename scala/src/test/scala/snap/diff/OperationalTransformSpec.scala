package snap.diff

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.repository.EditOp

/**
 * SPEC.md §6.3's transform table, one case per row, plus the acceptance suite's own
 * worked example (tests/22-ot-matrix.yaml).
 */
class OperationalTransformSpec extends AnyFunSuite with Matchers {

  private def retain(n: Long) = EditOp.Retain(n)
  private def delete(n: Long) = EditOp.Delete(n)
  private def insert(tokens: String*) = EditOp.Insert(tokens.toVector)

  test("P delete / Q delete: the same base token is discarded only once") {
    // tests/22-ot-matrix.yaml's exact case: base "0 1 2 3 4", P deletes "1","2",
    // Q (already-integrated context) deletes "1" only.
    val p = Vector(retain(1), delete(2), retain(2))
    val q = Vector(retain(1), delete(1), retain(3))
    OperationalTransform.transform(p, q) shouldBe Vector(retain(1), delete(1), retain(2))
  }

  test("P retain / Q retain: both keep the token") {
    OperationalTransform.transform(Vector(retain(3)), Vector(retain(3))) shouldBe Vector(retain(3))
  }

  test("P delete / Q retain: the delete survives") {
    OperationalTransform.transform(Vector(delete(1), retain(2)), Vector(retain(3))) shouldBe
      Vector(delete(1), retain(2))
  }

  test("P retain / Q delete: a retained token already deleted by context stays deleted") {
    // tests/22-ot-matrix.yaml's "rd" case: P retains everything (plus a trailing
    // append), Q deletes one base token — the transformed P must consume it as nothing,
    // and the surrounding retains coalesce across that gap.
    val p = Vector(retain(5), insert("A\n"))
    val q = Vector(retain(1), delete(1), retain(3))
    OperationalTransform.transform(p, q) shouldBe Vector(retain(4), insert("A\n"))
  }

  test("Q insert has priority and becomes a retain of its token count") {
    OperationalTransform.transform(Vector(retain(2)), Vector(insert("new\n"), retain(2))) shouldBe
      Vector(retain(3))
  }

  test("P insert passes through unchanged, consuming only P") {
    OperationalTransform.transform(Vector(insert("hi\n"), retain(2)), Vector(retain(2))) shouldBe
      Vector(insert("hi\n"), retain(2))
  }

  test("a Q insert before a P deletion survives, since deletion consumes only base tokens") {
    // tests/22-ot-matrix.yaml's "survive" case: base "0 1 2 3 4"; P deletes token "1"
    // (base-relative); Q inserts "B" before token "1" then retains the rest.
    val p = Vector(retain(1), delete(1), retain(3))
    val q = Vector(retain(1), insert("B\n"), retain(4))
    OperationalTransform.transform(p, q) shouldBe Vector(retain(2), delete(1), retain(3))
  }

  test("trailing insertions on both sides are each processed with their applicable row") {
    val p = Vector(retain(1), insert("P-tail\n"))
    val q = Vector(retain(1), insert("Q-tail\n"))
    // Q's trailing insert is retained over (priority), then P's own trailing insert
    // passes through afterward.
    OperationalTransform.transform(p, q) shouldBe Vector(retain(2), insert("P-tail\n"))
  }

  test("adjacent output operations are coalesced") {
    val p = Vector(retain(1), retain(1), retain(1))
    val q = Vector(retain(3))
    OperationalTransform.transform(p, q) shouldBe Vector(retain(3))
  }

  test("a multi-token Q insert's retain coalesces correctly with a preceding retain") {
    // Regression for tests/21-version-algebra.yaml: coalescing must sum each op's own
    // count rather than count the number of ops, or a Q insert-priority retain whose
    // token count is > 1 gets undercounted when merged with an adjacent retain.
    val p = Vector(retain(1), insert("A2\n"))
    val q = Vector(retain(1), insert("B1\n", "B2\n"))
    OperationalTransform.transform(p, q) shouldBe Vector(retain(3), insert("A2\n"))
  }
}
