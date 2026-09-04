package snap.repository

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.SnapError
import snap.version.Version

class RepositoryValidatorSpec extends AnyFunSuite with Matchers {

  private def textChange(path: String): Change = Change.Text(path, Vector.empty)

  private def patch(author: String, revision: Long, base: Version, path: String): Patch =
    Patch(author, revision, base, "msg", Vector(textChange(path)))

  test("validate accepts an empty repository") {
    RepositoryValidator.validate(Version.Empty, Vector.empty) shouldBe Repository(
      Version.Empty,
      Vector.empty
    )
  }

  test("validate accepts a simple linear history reachable from frontier") {
    val p1 = patch("a@x", 1, Version.Empty, "f")
    val p2 = patch("a@x", 2, Version.fromPairs(Seq("a@x" -> 1L)), "g")
    val frontier = Version.fromPairs(Seq("a@x" -> 2L))
    RepositoryValidator.validate(frontier, Vector(p1, p2)) shouldBe Repository(
      frontier,
      Vector(p1, p2)
    )
  }

  test("validate rejects a frontier referencing a dot with no patch") {
    val frontier = Version.fromPairs(Seq("a@x" -> 1L))
    val ex = the[SnapError] thrownBy RepositoryValidator.validate(frontier, Vector.empty)
    ex.message should include("missing a@x")
  }

  test("validate rejects a base referencing a dot with no patch") {
    val p = patch("a@x", 2, Version.fromPairs(Seq("a@x" -> 1L)), "f") // revision 1 never present
    val frontier = Version.fromPairs(Seq("a@x" -> 2L))
    val ex = the[SnapError] thrownBy RepositoryValidator.validate(frontier, Vector(p))
    ex.message should include("missing a@x")
  }

  test("validate rejects a patch unreachable from frontier") {
    val p = patch("a@x", 1, Version.Empty, "f")
    val ex = the[SnapError] thrownBy RepositoryValidator.validate(Version.Empty, Vector(p))
    ex.message shouldBe "unreachable patch: a@x revision 1"
  }

  test("validate rejects a two-patch mutual (cross-author) cycle") {
    val a1 = patch("a@x", 1, Version.fromPairs(Seq("b@x" -> 1L)), "a")
    val b1 = patch("b@x", 1, Version.fromPairs(Seq("a@x" -> 1L)), "b")
    val frontier = Version.fromPairs(Seq("a@x" -> 1L, "b@x" -> 1L))
    val ex = the[SnapError] thrownBy RepositoryValidator.validate(frontier, Vector(a1, b1))
    ex.message shouldBe "cyclic or incomplete patch history"
  }

  test("validate rejects patches not sorted by author then revision") {
    val a1 = patch("a@x", 1, Version.Empty, "a")
    val b1 = patch("b@x", 1, Version.Empty, "b")
    val frontier = Version.fromPairs(Seq("a@x" -> 1L, "b@x" -> 1L))
    val ex = the[SnapError] thrownBy RepositoryValidator.validate(frontier, Vector(b1, a1))
    ex.message should include("sorted")
  }

  test("validate rejects a duplicate patch dot") {
    val a1 = patch("a@x", 1, Version.Empty, "a")
    val a1Again = patch("a@x", 1, Version.Empty, "different")
    val frontier = Version.fromPairs(Seq("a@x" -> 1L))
    val ex = the[SnapError] thrownBy RepositoryValidator.validate(frontier, Vector(a1, a1Again))
    ex.message shouldBe "duplicate patch dot: a@x revision 1"
  }

  test("integrationOrder puts causal dependencies before the patches that need them") {
    val p1 = patch("a@x", 1, Version.Empty, "f")
    val p2 = patch("a@x", 2, Version.fromPairs(Seq("a@x" -> 1L)), "g")
    RepositoryValidator.integrationOrder(Vector(p2, p1)) shouldBe Vector(p1, p2)
  }

  test("integrationOrder picks the least ready patch by Snap order of result version") {
    // Two concurrent single-contributor patches from the empty base: results (a@x->1) and
    // (b@x->1). Snap order compares the sorted union of contributors ("a@x" before
    // "b@x") ascending by counter there first; (a@x->1) has a=1 at that leading position
    // while (b@x->1) has a=0, so (b@x->1) — the *lower* value there — sorts first, even
    // though "a@x" itself sorts before "b@x" as a contributor id.
    val a1 = patch("a@x", 1, Version.Empty, "a")
    val b1 = patch("b@x", 1, Version.Empty, "b")
    RepositoryValidator.integrationOrder(Vector(a1, b1)) shouldBe Vector(b1, a1)
  }

  test("integrationOrder orders a mix of concurrent and causally dependent patches") {
    val a1 = patch("a@x", 1, Version.Empty, "a1")
    val b1 = patch("b@x", 1, Version.Empty, "b1")
    val a2 = patch("a@x", 2, Version.fromPairs(Seq("a@x" -> 1L, "b@x" -> 1L)), "a2")
    val frontier = Version.fromPairs(Seq("a@x" -> 2L, "b@x" -> 1L))
    // b1 and a1 are both initially ready; b1 sorts first by the rule above. a2 only
    // becomes ready once both are integrated, since its base needs (a@x,1) and (b@x,1).
    RepositoryValidator.integrationOrder(Vector(a1, a2, b1)) shouldBe Vector(b1, a1, a2)
    RepositoryValidator.validate(frontier, Vector(a1, a2, b1).sortBy(_.dot)) shouldBe
      Repository(frontier, Vector(a1, a2, b1).sortBy(_.dot))
  }
}
