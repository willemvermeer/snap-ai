package snap.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.json.{Json, JsonParser}

class CliSpec extends AnyFunSuite with Matchers {

  private case class Result(exitCode: Int, stdout: String, stderr: String)

  private def run(
    args: Vector[String],
    vars: Map[String, String] = Map.empty,
    cwd: Path = Files.createTempDirectory("snap-cli-")
  ): Result = {
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val exitCode = Cli.run(
      args,
      Cli.Env(
        vars = vars,
        cwd = cwd,
        stdout = new PrintStream(out, true, UTF_8),
        stderr = new PrintStream(err, true, UTF_8),
        stdoutIsTty = false,
        stderrIsTty = false
      )
    )
    Result(exitCode, new String(out.toByteArray, UTF_8), new String(err.toByteArray, UTF_8))
  }

  test("--version prints the semver line and exits 0") {
    val result = run(Vector("--version"))
    result.exitCode shouldBe 0
    result.stdout should fullyMatch regex """snap \d+\.\d+\.\d+\n"""
    result.stderr shouldBe ""
  }

  test("--version rejects extra arguments") {
    val result = run(Vector("--version", "extra"))
    result.exitCode shouldBe 1
    result.stdout shouldBe ""
    result.stderr shouldBe "snap: invalid command or arguments\n"
  }

  test("--help prints usage and exits 0") {
    val result = run(Vector("--help"))
    result.exitCode shouldBe 0
    result.stdout should not be empty
    result.stderr shouldBe ""
  }

  test("-h prints usage and exits 0") {
    val result = run(Vector("-h"))
    result.exitCode shouldBe 0
    result.stdout should not be empty
    result.stderr shouldBe ""
  }

  test("--help rejects extra arguments") {
    val result = run(Vector("--help", "extra"))
    result.exitCode shouldBe 1
    result.stdout shouldBe ""
    result.stderr shouldBe "snap: invalid command or arguments\n"
  }

  test("an unrecognized command is a grammar error") {
    val result = run(Vector("unknown"))
    result.exitCode shouldBe 1
    result.stdout shouldBe ""
    result.stderr shouldBe "snap: invalid command or arguments\n"
  }

  test("an invalid SNAP_COLOR value is rejected before dispatch, even for a valid command") {
    val result = run(Vector("--version"), vars = Map("SNAP_COLOR" -> "sometimes"))
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: SNAP_COLOR must be auto, always, or never\n"
  }

  test("config --global writes $HOME/.snapconfig.json and prints nothing") {
    val home = Files.createTempDirectory("snap-home-")
    val result = run(
      Vector("config", "--global", "contributor.id", "alice@example.com"),
      vars = Map("HOME" -> home.toString)
    )
    result.exitCode shouldBe 0
    result.stdout shouldBe ""
    result.stderr shouldBe ""
    JsonParser.parse(
      new String(Files.readAllBytes(home.resolve(".snapconfig.json")), UTF_8)
    ) shouldBe
      Json.Obj(Vector("contributor" -> Json.Obj(Vector("id" -> Json.Str("alice@example.com")))))
  }

  test("config without --global requires a repository") {
    val outside = Files.createTempDirectory("snap-outside-")
    val result = run(Vector("config", "contributor.id", "alice@example.com"), cwd = outside)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: not a Snap repository\n"
  }

  test("config without --global writes into the nearest .snap directory") {
    val repo = Files.createTempDirectory("snap-repo-")
    val snapDir = Files.createDirectory(repo.resolve(".snap"))
    val nested = Files.createDirectories(repo.resolve("nested"))

    val result = run(Vector("config", "contributor.id", "bob@example.com"), cwd = nested)
    result.exitCode shouldBe 0
    JsonParser.parse(new String(Files.readAllBytes(snapDir.resolve("config.json")), UTF_8)) shouldBe
      Json.Obj(Vector("contributor" -> Json.Obj(Vector("id" -> Json.Str("bob@example.com")))))
  }

  test("config rejects an invalid contributor id") {
    val repo = Files.createTempDirectory("snap-repo-")
    Files.createDirectory(repo.resolve(".snap"))
    val result = run(Vector("config", "contributor.id", "not-an-id"), cwd = repo)
    result.exitCode shouldBe 1
    result.stderr shouldBe "snap: invalid contributor id: not-an-id\n"
  }

  // Grammar cases mirrored from tests/24-cli-grammar-matrix.yaml, scoped to what unit 1 implements.
  test("config grammar rejects misplaced, duplicated, or missing --global / arguments") {
    val repo = Files.createTempDirectory("snap-repo-")
    Files.createDirectory(repo.resolve(".snap"))

    def rejected(args: Vector[String]): Unit = {
      val result = run(args, cwd = repo)
      result.exitCode shouldBe 1
      result.stderr shouldBe "snap: invalid command or arguments\n"
    }

    rejected(Vector("config", "contributor.id", "a@x", "--global"))
    rejected(Vector("config", "--global", "--global", "contributor.id", "a@x"))
    rejected(Vector("config", "--global", "contributor.id"))
  }
}
