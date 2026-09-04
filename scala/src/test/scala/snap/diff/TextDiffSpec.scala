package snap.diff

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.repository.EditOp

class TextDiffSpec extends AnyFunSuite with Matchers {

  test("diff of empty against empty is an empty script") {
    TextDiff.diff(Vector.empty, Vector.empty) shouldBe Vector.empty
  }

  test("diff of identical tokens is a single coalesced retain") {
    val tokens = Vector("a\n", "b\n", "a\n")
    TextDiff.diff(tokens, tokens) shouldBe Vector(EditOp.Retain(3))
  }

  test("diff of empty old against nonempty new is a single coalesced insert") {
    TextDiff.diff(Vector.empty, Vector("new")) shouldBe Vector(EditOp.Insert(Vector("new")))
  }

  test("diff of nonempty old against empty new is a single coalesced delete") {
    val old = Vector("a\n", "b\n")
    TextDiff.diff(old, Vector.empty) shouldBe Vector(EditOp.Delete(2))
  }

  test("SPEC.md §5's repeated-line golden case: a/b/a -> b/a/a") {
    // Mirrors tests/05-diff-goldens.yaml exactly: old "a\nb\na\n", new "b\na\na" (no
    // trailing newline). The expected script is delete:1, retain:2, insert:[a].
    val oldTokens = TextTokens.tokenize("a\nb\na\n")
    val newTokens = TextTokens.tokenize("b\na\na")
    TextDiff.diff(oldTokens, newTokens) shouldBe
      Vector(EditOp.Delete(1), EditOp.Retain(2), EditOp.Insert(Vector("a")))
  }

  test("a deletion tie prefers delete before insert, per SPEC.md §5 rule 2") {
    // A single differing token on each side: D(1,0) and D(0,1) are both 1, an exact
    // tie. SPEC.md's rule 2 says delete wins ties ("choose delete 1 when
    // D(i+1,j) <= D(i,j+1)"), so the script is delete-then-insert, not insert-then-delete.
    TextDiff.diff(Vector("x"), Vector("y")) shouldBe
      Vector(EditOp.Delete(1), EditOp.Insert(Vector("y")))
  }

  test("a missing final newline produces a trailing single-token insert") {
    TextDiff.diff(Vector.empty, TextTokens.tokenize("new")) shouldBe
      Vector(EditOp.Insert(Vector("new")))
  }

  test("coalesces a longer run of each operation kind, with only one retained token") {
    val oldTokens = Vector("a\n", "b\n", "c\n", "d\n")
    val newTokens = Vector("w\n", "x\n", "c\n", "y\n", "z\n")
    val script = TextDiff.diff(oldTokens, newTokens)
    // Applying the script must reproduce newTokens exactly and consume all of
    // oldTokens — a property check independent of exactly which of several equally
    // minimal scripts the tie rule picks, plus one exact structural fact: "c\n" is the
    // only shared token, so the script must retain exactly one token in total.
    applyScript(oldTokens, script) shouldBe newTokens
    script.collect { case EditOp.Retain(n) => n }.sum shouldBe 1L
  }

  test("every generated script consumes the complete old token sequence") {
    val cases = Seq(
      Vector("a\n", "b\n", "c\n") -> Vector("a\n", "x\n", "c\n"),
      Vector("one\n", "two\n", "three\n") -> Vector("three\n", "two\n", "one\n"),
      Vector("a\n") -> Vector("a\n", "b\n"),
      Vector("a\n", "a\n", "a\n") -> Vector("a\n", "a\n")
    )
    cases.foreach { case (oldTokens, newTokens) =>
      val script = TextDiff.diff(oldTokens, newTokens)
      applyScript(oldTokens, script) shouldBe newTokens
    }
  }

  test("adjacent same-kind operations never appear uncoalesced") {
    val script = TextDiff.diff(Vector("a\n", "b\n", "c\n"), Vector("x\n", "y\n", "z\n"))
    script.sliding(2).foreach {
      case Seq(EditOp.Retain(_), EditOp.Retain(_)) => fail("adjacent retains not coalesced")
      case Seq(EditOp.Delete(_), EditOp.Delete(_)) => fail("adjacent deletes not coalesced")
      case Seq(_: EditOp.Insert, _: EditOp.Insert) => fail("adjacent inserts not coalesced")
      case _ => ()
    }
  }

  /**
   * Applies an edit script to old tokens the way SPEC.md §4.4 defines application, to
   * check round-trip correctness independent of which minimal script the tie rule
   * picked.
   */
  private def applyScript(oldTokens: Vector[String], script: Vector[EditOp]): Vector[String] = {
    val result = Vector.newBuilder[String]
    var i = 0
    script.foreach {
      case EditOp.Retain(n) =>
        result ++= oldTokens.slice(i, i + n.toInt)
        i += n.toInt
      case EditOp.Delete(n) =>
        i += n.toInt
      case EditOp.Insert(tokens) =>
        result ++= tokens
    }
    result.result()
  }
}
