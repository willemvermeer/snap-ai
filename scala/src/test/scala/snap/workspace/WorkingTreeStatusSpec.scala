package snap.workspace

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class WorkingTreeStatusSpec extends AnyFunSuite with Matchers {

  private def bytes(s: String): Vector[Byte] = s.getBytes("UTF-8").toVector

  test("reports no changes for identical trees") {
    val tree = Map("a" -> bytes("x"))
    WorkingTreeStatus.compare(tree, tree) shouldBe Vector.empty
  }

  test("reports Added, Modified, and Deleted correctly") {
    val current =
      Map("changed" -> bytes("old"), "removed" -> bytes("gone"), "same" -> bytes("same"))
    val working =
      Map("changed" -> bytes("new"), "added" -> bytes("brand new"), "same" -> bytes("same"))
    WorkingTreeStatus.compare(current, working) shouldBe Vector(
      "added" -> PathStatus.Added,
      "changed" -> PathStatus.Modified,
      "removed" -> PathStatus.Deleted
    )
  }

  test("sorts results by unsigned UTF-8 byte order, not default String order") {
    val current = Map.empty[String, Vector[Byte]]
    val working = Map("b" -> bytes("1"), "a" -> bytes("1"), "a/b" -> bytes("1"))
    WorkingTreeStatus.compare(current, working).map(_._1) shouldBe Vector("a", "a/b", "b")
  }

  test("does not report a path whose bytes happen to compare equal") {
    val tree1 = Map("a" -> Vector[Byte](1, 2, 3))
    val tree2 = Map("a" -> Vector[Byte](1, 2, 3))
    WorkingTreeStatus.compare(tree1, tree2) shouldBe Vector.empty
  }
}
