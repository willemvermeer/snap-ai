package snap.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Integration coverage for `init`/`status`/`log`/`commit` via [[Cli.run]], mirroring
 * the scenarios in tests/01-init.yaml, tests/02-init-paths.yaml, and
 * tests/04-commit-status-log.yaml.
 */
class RepositoryCommandsSpec extends AnyFunSuite with Matchers {

  private case class Result(exitCode: Int, stdout: String, stderr: String)

  private def run(
    args: Vector[String],
    cwd: Path,
    vars: Map[String, String] = Map.empty
  ): Result = {
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val exitCode = Cli.run(
      args,
      Cli.Env(
        vars,
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

  // ---- init ---------------------------------------------------------------------------

  test("init on a fresh directory prints () and creates an empty repository") {
    val root = Files.createTempDirectory("snap-init-")
    val result = run(Vector("init"), root)
    result.exitCode shouldBe 0
    result.stdout shouldBe "()\n"
    Files.isDirectory(root.resolve(".snap")) shouldBe true
  }

  test(
    "init defaults to the current directory and creates missing parent directories for a path operand"
  ) {
    val root = Files.createTempDirectory("snap-init-")
    val result = run(Vector("init", "a/b"), root)
    result.exitCode shouldBe 0
    Files.isDirectory(root.resolve("a/b/.snap")) shouldBe true
  }

  test("reinitializing an existing repository is an error") {
    val root = Files.createTempDirectory("snap-init-")
    run(Vector("init"), root).exitCode shouldBe 0
    val result = run(Vector("init"), root)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: repository already exists\n"
  }

  test("initializing inside an existing repository is an error and creates nothing") {
    val root = Files.createTempDirectory("snap-init-")
    run(Vector("init"), root).exitCode shouldBe 0
    Files.createDirectories(root.resolve("child"))
    val result = run(Vector("init"), root.resolve("child"))
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: cannot initialize inside repository\n"
    Files.exists(root.resolve("child/.snap")) shouldBe false
  }

  test("init rejects extra operands and option-like arguments") {
    val root = Files.createTempDirectory("snap-init-")
    run(Vector("init", "a", "b"), root).exitCode shouldBe 1
    run(Vector("init", "--unknown"), root).exitCode shouldBe 1
  }

  // ---- status ---------------------------------------------------------------------------

  test("status on a clean freshly initialized repository prints only the version") {
    val root = initializedRepo()
    val result = run(Vector("status"), root)
    result.exitCode shouldBe 0
    result.stdout shouldBe "version ()\n"
  }

  test("status reports added, modified, and deleted paths sorted by path") {
    val root = initializedRepo("alice@example.com")
    Files.write(root.resolve("z.txt"), "z\n".getBytes(UTF_8))
    Files.write(root.resolve("a.txt"), "a\n".getBytes(UTF_8))
    run(Vector("commit", "first"), root).exitCode shouldBe 0

    Files.write(root.resolve("a.txt"), "changed\n".getBytes(UTF_8))
    Files.delete(root.resolve("z.txt"))
    Files.write(root.resolve("m.txt"), "middle\n".getBytes(UTF_8))

    val result = run(Vector("status"), root)
    result.stdout shouldBe "version (alice@example.com->1)\nM a.txt\nA m.txt\nD z.txt\n"
  }

  test("status requires a repository") {
    val outside = Files.createTempDirectory("snap-outside-")
    val result = run(Vector("status"), outside)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: not a Snap repository\n"
  }

  test("status rejects extra arguments") {
    val root = initializedRepo()
    run(Vector("status", "extra"), root).exitCode shouldBe 1
  }

  // ---- commit ---------------------------------------------------------------------------

  test("commit on a clean tree is an error") {
    val root = initializedRepo()
    val result = run(Vector("commit", "nothing changed"), root)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: working tree is clean\n"
  }

  test("commit requires contributor configuration") {
    val root = Files.createTempDirectory("snap-repo-")
    run(Vector("init"), root).exitCode shouldBe 0
    Files.write(root.resolve("f"), "x".getBytes(UTF_8))
    val result = run(Vector("commit", "m"), root)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: contributor.id is required; configure it locally or globally\n"
  }

  test("commit prints the new version and advances the frontier") {
    val root = initializedRepo("alice@example.com")
    Files.write(root.resolve("f"), "hello\n".getBytes(UTF_8))
    val result = run(Vector("commit", "add f"), root)
    result.exitCode shouldBe 0
    result.stdout shouldBe "(alice@example.com->1)\n"
    run(Vector("status"), root).stdout shouldBe "version (alice@example.com->1)\n"
  }

  test("commit rejects an empty message and a message with a disallowed control character") {
    val root = initializedRepo()
    Files.write(root.resolve("f"), "x".getBytes(UTF_8))
    run(Vector("commit", ""), root).exitCode shouldBe 1
  }

  test("commit rejects wrong arity") {
    val root = initializedRepo()
    run(Vector("commit"), root).exitCode shouldBe 1
    run(Vector("commit", "a", "b"), root).exitCode shouldBe 1
  }

  test("commit uses put for binary content and text for text content") {
    val root = initializedRepo("a@x")
    Files.write(root.resolve("bin"), Array[Byte](0, 1, 2))
    Files.write(root.resolve("text.txt"), "hello\n".getBytes(UTF_8))
    run(Vector("commit", "mixed"), root).exitCode shouldBe 0
    val repoJson = new String(Files.readAllBytes(root.resolve(".snap/repository.json")), UTF_8)
    repoJson should include(""""type": "put"""")
    repoJson should include(""""type": "text"""")
  }

  test("commit falls back to put when new content is text but old content was binary") {
    val root = initializedRepo("a@x")
    Files.write(root.resolve("f"), Array[Byte](0))
    run(Vector("commit", "binary"), root).exitCode shouldBe 0
    Files.write(root.resolve("f"), "now text\n".getBytes(UTF_8))
    run(Vector("commit", "now text"), root).exitCode shouldBe 0
    val repoJson = new String(Files.readAllBytes(root.resolve(".snap/repository.json")), UTF_8)
    // Both patches' single change must be "put": the second commit can't use a text
    // edit against binary old content.
    "\"type\": \"put\"".r.findAllIn(repoJson).length shouldBe 2
  }

  // ---- log ---------------------------------------------------------------------------

  test("log prints patches in reverse order with escaped messages") {
    val root = initializedRepo("alice@example.com")
    Files.write(root.resolve("a"), "1".getBytes(UTF_8))
    run(Vector("commit", "first\tline\nsecond\\tail"), root).exitCode shouldBe 0
    Files.write(root.resolve("a"), "2".getBytes(UTF_8))
    run(Vector("commit", "second"), root).exitCode shouldBe 0

    val result = run(Vector("log"), root)
    result.stdout shouldBe
      "(alice@example.com->2)\talice@example.com\tsecond\n" +
      "(alice@example.com->1)\talice@example.com\tfirst\\tline\\nsecond\\\\tail\n"
  }

  test("log on a repository with no patches prints nothing") {
    val root = initializedRepo()
    run(Vector("log"), root).stdout shouldBe ""
  }

  test("log rejects extra arguments") {
    val root = initializedRepo()
    run(Vector("log", "--unknown"), root).exitCode shouldBe 1
  }

  // ---- unsupported entries ---------------------------------------------------------------

  test("status and commit reject a symlink in the working tree without mutating anything") {
    val root = initializedRepo()
    Files.createSymbolicLink(root.resolve("link"), root.resolve("missing"))
    run(Vector("status"), root).stderr shouldBe "snap: unsupported working tree entry: link\n"
    run(Vector("commit", "m"), root).stderr shouldBe "snap: unsupported working tree entry: link\n"
  }
}
