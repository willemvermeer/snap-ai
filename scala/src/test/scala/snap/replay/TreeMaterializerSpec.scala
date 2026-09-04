package snap.replay

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.repository.{Change, EditOp, Patch, Repository}
import snap.version.Version

class TreeMaterializerSpec extends AnyFunSuite with Matchers {

  private def utf8(s: String): Vector[Byte] = s.getBytes("UTF-8").toVector

  test("materializes the empty repository as an empty tree") {
    TreeMaterializer.materialize(Repository(Version.Empty, Vector.empty)) shouldBe Map.empty
  }

  test("materializes a single text-creating patch") {
    val patch = Patch(
      "a@x",
      1,
      Version.Empty,
      "m",
      Vector(Change.Text("f", Vector(EditOp.Insert(Vector("hello\n")))))
    )
    val repo = Repository(Version.fromPairs(Seq("a@x" -> 1L)), Vector(patch))
    TreeMaterializer.materialize(repo) shouldBe Map("f" -> utf8("hello\n"))
  }

  test("materializes a put change verbatim, including binary bytes") {
    val bytes: Vector[Byte] = Vector(0, 1, -1, 127)
    val patch = Patch("a@x", 1, Version.Empty, "m", Vector(Change.Put("f", bytes)))
    val repo = Repository(Version.fromPairs(Seq("a@x" -> 1L)), Vector(patch))
    TreeMaterializer.materialize(repo) shouldBe Map("f" -> bytes)
  }

  test("a later patch's text edit is applied against the accumulated tree") {
    val p1 = Patch(
      "a@x",
      1,
      Version.Empty,
      "m1",
      Vector(Change.Text("f", Vector(EditOp.Insert(Vector("one\n", "two\n")))))
    )
    val p2 = Patch(
      "a@x",
      2,
      Version.fromPairs(Seq("a@x" -> 1L)),
      "m2",
      Vector(Change.Text("f", Vector(EditOp.Delete(1), EditOp.Retain(1))))
    )
    val repo = Repository(Version.fromPairs(Seq("a@x" -> 2L)), Vector(p1, p2))
    TreeMaterializer.materialize(repo) shouldBe Map("f" -> utf8("two\n"))
  }

  test("a delete change removes the path from the tree") {
    val p1 = Patch("a@x", 1, Version.Empty, "m1", Vector(Change.Put("f", utf8("x"))))
    val p2 = Patch("a@x", 2, Version.fromPairs(Seq("a@x" -> 1L)), "m2", Vector(Change.Delete("f")))
    val repo = Repository(Version.fromPairs(Seq("a@x" -> 2L)), Vector(p1, p2))
    TreeMaterializer.materialize(repo) shouldBe Map.empty
  }

  test("independent paths from different patches coexist in the final tree") {
    val p1 = Patch("a@x", 1, Version.Empty, "m1", Vector(Change.Put("a", utf8("a"))))
    val p2 =
      Patch("a@x", 2, Version.fromPairs(Seq("a@x" -> 1L)), "m2", Vector(Change.Put("b", utf8("b"))))
    val repo = Repository(Version.fromPairs(Seq("a@x" -> 2L)), Vector(p1, p2))
    TreeMaterializer.materialize(repo) shouldBe Map("a" -> utf8("a"), "b" -> utf8("b"))
  }
}
