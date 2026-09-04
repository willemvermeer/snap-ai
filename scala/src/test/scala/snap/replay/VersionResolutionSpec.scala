package snap.replay

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.repository.{Change, Patch}
import snap.version.Version

class VersionResolutionSpec extends AnyFunSuite with Matchers {

  private def textPatch(author: String, revision: Long, base: Version, path: String): Patch =
    Patch(author, revision, base, "m", Vector(Change.Text(path, Vector.empty)))

  test("resolves the empty version against any patch set") {
    val p1 = textPatch("a@x", 1, Version.Empty, "f")
    VersionResolution.resolve(Version.Empty, Vector(p1)) shouldBe Some(Vector.empty)
  }

  test("resolves a version reachable through a linear chain") {
    val p1 = textPatch("a@x", 1, Version.Empty, "f")
    val p2 = textPatch("a@x", 2, Version.fromPairs(Seq("a@x" -> 1L)), "g")
    val target = Version.fromPairs(Seq("a@x" -> 1L))
    VersionResolution.resolve(target, Vector(p1, p2)) shouldBe Some(Vector(p1))
  }

  test("resolves an intermediate version, excluding later patches") {
    val p1 = textPatch("a@x", 1, Version.Empty, "f")
    val p2 = textPatch("a@x", 2, Version.fromPairs(Seq("a@x" -> 1L)), "g")
    val p3 = textPatch("a@x", 3, Version.fromPairs(Seq("a@x" -> 2L)), "h")
    VersionResolution.resolve(Version.fromPairs(Seq("a@x" -> 2L)), Vector(p1, p2, p3)) shouldBe
      Some(Vector(p1, p2))
  }

  test("returns None for a version referencing a dot that doesn't exist") {
    val target = Version.fromPairs(Seq("a@x" -> 1L))
    VersionResolution.resolve(target, Vector.empty) shouldBe None
  }

  test("returns None when only a transitively-required base dot is missing") {
    // p2 exists but references a base dot (a@x, 1) that isn't present.
    val p2 = textPatch("a@x", 2, Version.fromPairs(Seq("a@x" -> 1L)), "g")
    VersionResolution.resolve(Version.fromPairs(Seq("a@x" -> 2L)), Vector(p2)) shouldBe None
  }

  test("resolves a concurrent version pulling in patches from multiple contributors") {
    val a1 = textPatch("a@x", 1, Version.Empty, "a")
    val b1 = textPatch("b@x", 1, Version.Empty, "b")
    val target = Version.fromPairs(Seq("a@x" -> 1L, "b@x" -> 1L))
    VersionResolution.resolve(target, Vector(a1, b1)).map(_.toSet) shouldBe Some(Set(a1, b1))
  }
}
