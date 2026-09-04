package snap.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Integration coverage for `merge` via [[Cli.run]], mirroring
 * tests/09-local-merge.yaml, tests/10-merge-conflicts.yaml, tests/11-namespace-conflicts.yaml,
 * tests/16-dot-collision.yaml, and tests/20-dirty-merge.yaml.
 */
class MergeCommandSpec extends AnyFunSuite with Matchers {

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

  test("merge rejects wrong arity") {
    val root = initializedRepo()
    run(Vector("merge"), root).exitCode shouldBe 1
    run(Vector("merge", "a", "b"), root).exitCode shouldBe 1
  }

  test("merge reports an HTTP operand it cannot reach as a SnapError, not a crash") {
    val root = initializedRepo()
    val result = run(Vector("merge", "http://127.0.0.1:1/repo"), root)
    result.exitCode shouldBe 1
    result.stdout shouldBe ""
    result.stderr should startWith("snap: HTTP request failed")
  }

  test("merge rejects an operand that is not a Snap repository") {
    val root = initializedRepo()
    val notARepo = Files.createTempDirectory("snap-not-a-repo-")
    val result = run(Vector("merge", notARepo.toString), root)
    result.exitCode shouldBe 1
    result.stderr shouldBe s"snap: not a Snap repository: ${notARepo.toString}\n"
  }

  test("merging equal or already-contained history changes nothing and needs no clean tree") {
    val local = initializedRepo("a@x")
    Files.write(local.resolve("f"), "local\n".getBytes(UTF_8))
    run(Vector("commit", "local"), local).exitCode shouldBe 0

    val other = Files.createTempDirectory("snap-other-")
    run(Vector("init"), other).exitCode shouldBe 0

    // The working tree is dirty, but since the merge is a no-op it must still succeed.
    Files.write(local.resolve("dirty"), "uncommitted".getBytes(UTF_8))

    val result = run(Vector("merge", other.toString), local)
    result.exitCode shouldBe 0
    result.stdout shouldBe "(a@x->1)\n"
    result.stderr shouldBe ""
    new String(Files.readAllBytes(local.resolve("f")), UTF_8) shouldBe "local\n"
  }

  test("merge unions divergent history, installs the joined tree, and prints the new frontier") {
    val local = initializedRepo("a@x")
    Files.write(local.resolve("local.txt"), "local\n".getBytes(UTF_8))
    run(Vector("commit", "local"), local).exitCode shouldBe 0

    val other = initializedRepo("b@x")
    Files.write(other.resolve("remote.txt"), "remote\n".getBytes(UTF_8))
    run(Vector("commit", "remote"), other).exitCode shouldBe 0

    val result = run(Vector("merge", other.toString), local)
    result.exitCode shouldBe 0
    result.stdout shouldBe "(a@x->1,b@x->1)\n"
    result.stderr shouldBe ""
    new String(Files.readAllBytes(local.resolve("local.txt")), UTF_8) shouldBe "local\n"
    new String(Files.readAllBytes(local.resolve("remote.txt")), UTF_8) shouldBe "remote\n"

    // The merge itself authors no patch: only the two original commits appear in
    // history.
    val log = run(Vector("log"), local).stdout
    log should include("a@x\tlocal\n")
    log should include("b@x\tremote\n")
    log.linesIterator.length shouldBe 2
  }

  test("merge refuses a dirty working tree when it would actually change something") {
    val local = initializedRepo("a@x")
    Files.write(local.resolve("local.txt"), "local\n".getBytes(UTF_8))
    run(Vector("commit", "local"), local).exitCode shouldBe 0

    val other = initializedRepo("b@x")
    Files.write(other.resolve("remote.txt"), "remote\n".getBytes(UTF_8))
    run(Vector("commit", "remote"), other).exitCode shouldBe 0

    Files.write(local.resolve("dirty"), "uncommitted".getBytes(UTF_8))
    val result = run(Vector("merge", other.toString), local)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: working tree is dirty\n"
  }

  test("merge prints a sorted auto-resolved warning for each new conflict") {
    // Binary (non-text) content, so `commit` always builds a `put` change and the
    // §6.2 OT path (text-only) never applies, forcing the §6.4 path-level rule.
    val local = initializedRepo("a@x")
    Files.write(local.resolve("f"), Array[Byte](0, 1))
    run(Vector("commit", "base"), local).exitCode shouldBe 0

    val other = Files.createTempDirectory("snap-other-")
    run(Vector("init"), other).exitCode shouldBe 0
    run(Vector("config", "contributor.id", "b@x"), other).exitCode shouldBe 0
    run(Vector("merge", local.toString), other).exitCode shouldBe 0

    Files.write(local.resolve("f"), Array[Byte](0, 2))
    run(Vector("commit", "local put"), local).exitCode shouldBe 0
    Files.write(other.resolve("f"), Array[Byte](0, 3))
    run(Vector("commit", "other put"), other).exitCode shouldBe 0

    val result = run(Vector("merge", other.toString), local)
    result.exitCode shouldBe 0
    result.stderr shouldBe "warning: auto-resolved f: later-put-wins\n"
  }

  test("cross-repository dot collisions fail before changing local state") {
    val local = initializedRepo("a@x")
    Files.write(local.resolve("file.txt"), "local\n".getBytes(UTF_8))
    run(Vector("commit", "local"), local).exitCode shouldBe 0

    val other = Files.createTempDirectory("snap-other-")
    Files.createDirectories(other.resolve(".snap"))
    Files.write(
      other.resolve(".snap/repository.json"),
      ("""{"format":1,"frontier":[["a@x",1]],"patches":[""" +
        """{"author":"a@x","revision":1,"base":[],"message":"different",""" +
        """"changes":[{"type":"text","path":"file.txt","edit":[{"insert":["other\n"]}]}]}]}""")
        .getBytes(UTF_8)
    )

    val result = run(Vector("merge", other.toString), local)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: patch collision: a@x revision 1\n"
    new String(Files.readAllBytes(local.resolve("file.txt")), UTF_8) shouldBe "local\n"
  }
}
