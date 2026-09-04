package snap.replay

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.repository.{Change, EditOp, Patch}
import snap.version.Version

/**
 * Direct, no-CLI coverage of SPEC.md §6's replay engine — PLAN.md unit 7's own stopgap
 * ("cover it with local unit tests against the spec's worked cases"), encoding the
 * scenarios from tests/10-merge-conflicts.yaml, tests/11-namespace-conflicts.yaml, and
 * tests/17-concurrent-creates.yaml (verified by hand against those files' expected
 * output before writing this).
 */
class ReplayEngineSpec extends AnyFunSuite with Matchers {

  private def utf8(s: String): Vector[Byte] = s.getBytes("UTF-8").toVector
  private def textCreate(path: String, content: String): Change =
    Change.Text(path, Vector(EditOp.Insert(Vector(content))))
  private def textEdit(path: String, edit: EditOp*): Change = Change.Text(path, edit.toVector)

  private def seed(changes: Change*): Patch =
    Patch("seed@x", 1, Version.Empty, "seed", changes.toVector)
  private def seedVersion = Version.fromPairs(Seq("seed@x" -> 1L))

  test("identical concurrent edits collapse with no warning (rule 2)") {
    // Both alice and bob independently edit identical.txt "base" -> "same"; whichever
    // integrates second must not re-apply or conflict.
    val base = seed(textCreate("identical.txt", "base\n"))
    val bob = Patch(
      "bob@x",
      1,
      seedVersion,
      "bob",
      Vector(textEdit("identical.txt", EditOp.Delete(1), EditOp.Insert(Vector("same\n"))))
    )
    val alice =
      Patch(
        "alice@x",
        1,
        seedVersion,
        "alice",
        Vector(textEdit("identical.txt", EditOp.Delete(1), EditOp.Insert(Vector("same\n"))))
      )

    val target = Version.fromPairs(Seq("alice@x" -> 1L, "bob@x" -> 1L, "seed@x" -> 1L))
    val ordered = VersionResolution.resolve(target, Vector(base, bob, alice)).get
    val result = ReplayEngine.replay(ordered)

    result.tree("identical.txt") shouldBe utf8("same\n")
    result.warnings shouldBe Vector.empty
  }

  test("binary vs text edit: incompatible current content wins (put-wins, rule 6)") {
    // seed creates incompatible.txt as text; bob replaces it with binary; alice
    // (integrated after bob, base = seed) tries a text edit against now-binary content.
    val base = seed(textCreate("incompatible.txt", "base\n"))
    val bob = Patch(
      "bob@x",
      1,
      seedVersion,
      "bob",
      Vector(Change.Put("incompatible.txt", Vector[Byte](0, -1)))
    )
    val alice = Patch(
      "alice@x",
      1,
      seedVersion,
      "alice",
      Vector(textEdit("incompatible.txt", EditOp.Delete(1), EditOp.Insert(Vector("left text\n"))))
    )

    val target = Version.fromPairs(Seq("alice@x" -> 1L, "bob@x" -> 1L, "seed@x" -> 1L))
    val ordered = VersionResolution.resolve(target, Vector(base, bob, alice)).get
    val result = ReplayEngine.replay(ordered)

    result.tree("incompatible.txt") shouldBe Vector[Byte](0, -1)
    result.warnings shouldBe Vector("incompatible.txt" -> "put-wins")
  }

  test("two concurrent puts: the later-integrated put wins (later-put-wins, rule 5)") {
    val base = seed(textCreate("later-put.txt", "base\n"))
    val bob = Patch(
      "bob@x",
      1,
      seedVersion,
      "bob",
      Vector(textEdit("later-put.txt", EditOp.Delete(1), EditOp.Insert(Vector("right text\n"))))
    )
    val alice = Patch(
      "alice@x",
      1,
      seedVersion,
      "alice",
      Vector(Change.Put("later-put.txt", Vector[Byte](0, 1)))
    )

    val target = Version.fromPairs(Seq("alice@x" -> 1L, "bob@x" -> 1L, "seed@x" -> 1L))
    val ordered = VersionResolution.resolve(target, Vector(base, bob, alice)).get
    val result = ReplayEngine.replay(ordered)

    // alice sorts after bob in canonical order here (Snap order, verified against
    // tests/10-merge-conflicts.yaml), so her put is "later" and wins.
    result.tree("later-put.txt") shouldBe Vector[Byte](0, 1)
    result.warnings shouldBe Vector("later-put.txt" -> "later-put-wins")
  }

  test(
    "an edit against a concurrently deleted path: the earlier delete wins (delete-wins, rule 3)"
  ) {
    val base = seed(textCreate("delete.txt", "base\n"))
    val bob = Patch("bob@x", 1, seedVersion, "bob", Vector(Change.Delete("delete.txt")))
    val alice =
      Patch(
        "alice@x",
        1,
        seedVersion,
        "alice",
        Vector(textEdit("delete.txt", EditOp.Delete(1), EditOp.Insert(Vector("left\n"))))
      )

    val target = Version.fromPairs(Seq("alice@x" -> 1L, "bob@x" -> 1L, "seed@x" -> 1L))
    val ordered = VersionResolution.resolve(target, Vector(base, bob, alice)).get
    val result = ReplayEngine.replay(ordered)

    result.tree.get("delete.txt") shouldBe None
    result.warnings shouldBe Vector("delete.txt" -> "delete-wins")
  }

  test(
    "two concurrent creates at the same path: the later-integrated create wins (later-create-wins, rule 4)"
  ) {
    val alice =
      Patch("alice@x", 1, Version.Empty, "alice", Vector(textCreate("same.txt", "alice\n")))
    val bob = Patch("bob@x", 1, Version.Empty, "bob", Vector(textCreate("same.txt", "bob\n")))

    val target = Version.fromPairs(Seq("alice@x" -> 1L, "bob@x" -> 1L))
    val ordered = VersionResolution.resolve(target, Vector(alice, bob)).get
    val result = ReplayEngine.replay(ordered)

    // Matches tests/17-concurrent-creates.yaml: alice's create wins regardless of
    // which repository initiates the merge, because Snap order (not merge direction)
    // decides integration order.
    result.tree("same.txt") shouldBe utf8("alice\n")
    result.warnings shouldBe Vector("same.txt" -> "later-create-wins")
  }

  test(
    "a namespace conflict removes the conflicting descendant and installs the file (namespace-wins)"
  ) {
    // Matches tests/11-namespace-conflicts.yaml's first case: bob creates "a/b",
    // alice creates "a" as a plain file; bob sorts first (Snap order), so when alice
    // integrates, "a/b" already exists as a descendant of her new path "a".
    val bob = Patch("bob@x", 1, Version.Empty, "bob", Vector(textCreate("a/b", "descendant\n")))
    val alice = Patch("alice@x", 1, Version.Empty, "alice", Vector(textCreate("a", "ancestor\n")))

    val target = Version.fromPairs(Seq("alice@x" -> 1L, "bob@x" -> 1L))
    val ordered = VersionResolution.resolve(target, Vector(alice, bob)).get
    val result = ReplayEngine.replay(ordered)

    result.tree shouldBe Map("a" -> utf8("ancestor\n"))
    result.warnings shouldBe Vector("a/b" -> "namespace-wins")
  }

  test("a namespace conflict in the other direction removes the file and keeps the descendant") {
    // Matches tests/11-namespace-conflicts.yaml's second case: bob creates "x" (a
    // file), alice creates "x/y"; bob still sorts first, so alice's nested path
    // conflicts with bob's existing file at its own ancestor "x".
    val bob = Patch("bob@x", 1, Version.Empty, "bob", Vector(textCreate("x", "ancestor\n")))
    val alice =
      Patch("alice@x", 1, Version.Empty, "alice", Vector(textCreate("x/y", "descendant\n")))

    val target = Version.fromPairs(Seq("alice@x" -> 1L, "bob@x" -> 1L))
    val ordered = VersionResolution.resolve(target, Vector(alice, bob)).get
    val result = ReplayEngine.replay(ordered)

    result.tree shouldBe Map("x/y" -> utf8("descendant\n"))
    result.warnings shouldBe Vector("x" -> "namespace-wins")
  }

  test("OT: a genuinely concurrent text edit is transformed against the aggregate context") {
    // The exact tests/22-ot-matrix.yaml "dd" case: base "0\n1\n2\n3\n4\n"; bob deletes
    // token "1" only; alice (base = seed) deletes tokens "1" and "2".
    val base = seed(textCreate("f", "0\n1\n2\n3\n4\n"))
    val bob = Patch(
      "bob@x",
      1,
      seedVersion,
      "bob",
      Vector(textEdit("f", EditOp.Retain(1), EditOp.Delete(1), EditOp.Retain(3)))
    )
    val alice = Patch(
      "alice@x",
      1,
      seedVersion,
      "alice",
      Vector(textEdit("f", EditOp.Retain(1), EditOp.Delete(2), EditOp.Retain(2)))
    )

    val target = Version.fromPairs(Seq("alice@x" -> 1L, "bob@x" -> 1L, "seed@x" -> 1L))
    val ordered = VersionResolution.resolve(target, Vector(base, bob, alice)).get
    val result = ReplayEngine.replay(ordered)

    result.tree("f") shouldBe utf8("0\n3\n4\n")
    result.warnings shouldBe Vector.empty // line OT emits no warning
  }

  test("warnings are sorted by path then reason") {
    val bob = Patch(
      "bob@x",
      1,
      Version.Empty,
      "bob",
      Vector(textCreate("z/child", "z\n"), textCreate("a/child", "a\n"))
    )
    val alice = Patch(
      "alice@x",
      1,
      Version.Empty,
      "alice",
      Vector(textCreate("a", "a-file\n"), textCreate("z", "z-file\n"))
    )

    val target = Version.fromPairs(Seq("alice@x" -> 1L, "bob@x" -> 1L))
    val ordered = VersionResolution.resolve(target, Vector(alice, bob)).get
    val result = ReplayEngine.replay(ordered)

    result.warnings shouldBe Vector("a/child" -> "namespace-wins", "z/child" -> "namespace-wins")
  }
}
