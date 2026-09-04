package snap.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Integration coverage for `diff` and `revert` via [[Cli.run]], mirroring
 * tests/05-diff-goldens.yaml, tests/06-binary-and-empty.yaml, tests/07-revert.yaml, and
 * tests/16-dot-collision.yaml's `diff --repo` scenario.
 */
class DiffRevertCommandsSpec extends AnyFunSuite with Matchers {

  private case class Result(exitCode: Int, stdout: String, stderr: String)

  private def run(args: Vector[String], cwd: Path): Result = {
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val exitCode = Cli.run(
      args,
      Cli.Env(
        Map.empty,
        cwd,
        new PrintStream(out, true, UTF_8),
        new PrintStream(err, true, UTF_8),
        false,
        false
      )
    )
    Result(exitCode, new String(out.toByteArray, UTF_8), new String(err.toByteArray, UTF_8))
  }

  private def initializedRepo(author: String = "a@x"): Path = {
    val root = Files.createTempDirectory("snap-repo-")
    run(Vector("init"), root).exitCode shouldBe 0
    run(Vector("config", "contributor.id", author), root).exitCode shouldBe 0
    root
  }

  // ---- diff ---------------------------------------------------------------------------

  test("diff with no arguments compares the current tree to the working tree") {
    val root = initializedRepo()
    Files.write(root.resolve("f"), "hello\n".getBytes(UTF_8))
    run(Vector("diff"), root).stdout shouldBe
      "--- /dev/null\n+++ b/f\n@@ -1,0 +1,1 @@\n+hello\n"
  }

  test("diff with no differences prints nothing and succeeds") {
    val root = initializedRepo()
    val result = run(Vector("diff"), root)
    result.exitCode shouldBe 0
    result.stdout shouldBe ""
  }

  test("diff between two known versions") {
    val root = initializedRepo("a@x")
    Files.write(root.resolve("f"), "one\n".getBytes(UTF_8))
    run(Vector("commit", "one"), root).exitCode shouldBe 0
    Files.write(root.resolve("f"), "two\n".getBytes(UTF_8))
    run(Vector("commit", "two"), root).exitCode shouldBe 0

    val result = run(Vector("diff", "(a@x->1)", "(a@x->2)"), root)
    result.exitCode shouldBe 0
    result.stdout shouldBe "--- a/f\n+++ b/f\n@@ -1,1 +1,1 @@\n-one\n+two\n"
  }

  test("diff between equal versions prints nothing") {
    val root = initializedRepo("a@x")
    Files.write(root.resolve("f"), "one\n".getBytes(UTF_8))
    run(Vector("commit", "one"), root).exitCode shouldBe 0
    run(Vector("diff", "(a@x->1)", "(a@x->1)"), root).stdout shouldBe ""
  }

  test("diff rejects an unknown version") {
    val root = initializedRepo("a@x")
    val result = run(Vector("diff", "(a@x->1)", "()"), root)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: unknown version: (a@x->1)\n"
  }

  test("diff rejects a malformed version string") {
    val root = initializedRepo()
    val result = run(Vector("diff", "(a@x->01)", "()"), root)
    result.exitCode shouldBe 1
    result.stderr should include("invalid version")
  }

  test("diff rejects malformed grammar with its own usage message, not the generic one") {
    val root = initializedRepo()
    val result = run(Vector("diff", "()"), root)
    result.exitCode shouldBe 1
    result.stderr should include("usage: snap diff")
  }

  test("diff --repo resolves new against another local repository without importing it") {
    val local = initializedRepo("a@x")
    Files.write(local.resolve("file.txt"), "local\n".getBytes(UTF_8))
    run(Vector("commit", "local"), local).exitCode shouldBe 0

    val remote = Files.createTempDirectory("snap-remote-")
    run(Vector("init"), remote).exitCode shouldBe 0
    run(Vector("config", "contributor.id", "b@x"), remote).exitCode shouldBe 0
    Files.write(remote.resolve("other.txt"), "remote\n".getBytes(UTF_8))
    run(Vector("commit", "remote"), remote).exitCode shouldBe 0

    val result = run(Vector("diff", "()", "(b@x->1)", "--repo", remote.toString), local)
    result.exitCode shouldBe 0
    result.stdout shouldBe "--- /dev/null\n+++ b/other.txt\n@@ -1,0 +1,1 @@\n+remote\n"
  }

  test("diff --repo detects a patch collision at the same dot") {
    val local = initializedRepo("a@x")
    Files.write(local.resolve("file.txt"), "local\n".getBytes(UTF_8))
    run(Vector("commit", "local"), local).exitCode shouldBe 0

    val remote = Files.createTempDirectory("snap-remote-")
    Files.createDirectories(remote.resolve(".snap"))
    Files.write(
      remote.resolve(".snap/repository.json"),
      ("""{"format":1,"frontier":[["a@x",1]],"patches":[""" +
        """{"author":"a@x","revision":1,"base":[],"message":"different",""" +
        """"changes":[{"type":"text","path":"file.txt","edit":[{"insert":["remote\n"]}]}]}]}""")
        .getBytes(UTF_8)
    )

    val result = run(Vector("diff", "()", "(a@x->1)", "--repo", remote.toString), local)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: patch collision: a@x revision 1\n"
  }

  // ---- revert ---------------------------------------------------------------------------

  test("revert restores an earlier version, advancing the frontier forward") {
    val root = initializedRepo("a@x")
    Files.write(root.resolve("f"), "one\n".getBytes(UTF_8))
    run(Vector("commit", "one"), root).exitCode shouldBe 0
    Files.write(root.resolve("f"), "two\n".getBytes(UTF_8))
    run(Vector("commit", "two"), root).exitCode shouldBe 0

    val result = run(Vector("revert", "(a@x->1)"), root)
    result.exitCode shouldBe 0
    result.stdout shouldBe "(a@x->3)\n"
    new String(Files.readAllBytes(root.resolve("f")), UTF_8) shouldBe "one\n"
  }

  test("revert records a 'revert to <version>' message") {
    val root = initializedRepo("a@x")
    Files.write(root.resolve("f"), "one\n".getBytes(UTF_8))
    run(Vector("commit", "one"), root).exitCode shouldBe 0
    Files.write(root.resolve("f"), "two\n".getBytes(UTF_8))
    run(Vector("commit", "two"), root).exitCode shouldBe 0
    run(Vector("revert", "(a@x->1)"), root).exitCode shouldBe 0

    run(Vector("log"), root).stdout should include("revert to (a@x->1)")
  }

  test("revert to the already-current version is an error") {
    val root = initializedRepo("a@x")
    Files.write(root.resolve("f"), "one\n".getBytes(UTF_8))
    run(Vector("commit", "one"), root).exitCode shouldBe 0
    val result = run(Vector("revert", "(a@x->1)"), root)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: target tree is already current\n"
  }

  test("revert refuses a dirty working tree") {
    val root = initializedRepo("a@x")
    Files.write(root.resolve("f"), "one\n".getBytes(UTF_8))
    run(Vector("commit", "one"), root).exitCode shouldBe 0
    Files.write(root.resolve("dirty"), "uncommitted".getBytes(UTF_8))
    val result = run(Vector("revert", "()"), root)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: working tree is dirty\n"
  }

  test("revert rejects an unknown version") {
    val root = initializedRepo("a@x")
    val result = run(Vector("revert", "(a@x->1)"), root)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: unknown version: (a@x->1)\n"
  }

  test("revert reports unknown version even when no contributor is configured") {
    // Regression: an unresolvable target version must be reported before the
    // contributor-configuration requirement, since the version check needs no author.
    val root = Files.createTempDirectory("snap-repo-")
    run(Vector("init"), root).exitCode shouldBe 0
    val result = run(Vector("revert", "(unknown@x->1)"), root)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: unknown version: (unknown@x->1)\n"
  }

  test("revert rejects wrong arity") {
    val root = initializedRepo()
    run(Vector("revert"), root).exitCode shouldBe 1
    run(Vector("revert", "()", "extra"), root).exitCode shouldBe 1
  }
}
