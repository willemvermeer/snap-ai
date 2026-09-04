package snap.config

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.SnapError

class ConfigSpec extends AnyFunSuite with Matchers {

  private def tempFile(text: String) = {
    val dir = Files.createTempDirectory("snap-config-")
    val path = dir.resolve("config.json")
    Files.write(path, text.getBytes(UTF_8))
    path
  }

  test("read returns None for a missing file") {
    val dir = Files.createTempDirectory("snap-config-")
    Config.read(dir.resolve("config.json")) shouldBe None
  }

  test("read decodes a well-formed config file") {
    val path = tempFile("""{"contributor":{"id":"alice@example.com"}}""")
    Config.read(path) shouldBe Some(ContributorConfig("alice@example.com"))
  }

  test("read rejects malformed JSON") {
    val path = tempFile("not json")
    val ex = the[SnapError] thrownBy Config.read(path)
    ex.message should include("invalid JSON")
  }

  test("read rejects duplicate JSON keys") {
    val path = tempFile("""{"contributor":{"id":"a@x","id":"b@x"}}""")
    val ex = the[SnapError] thrownBy Config.read(path)
    ex.message should include("duplicate JSON key")
  }

  test("read rejects unknown top-level fields") {
    val path = tempFile("""{"contributor":{"id":"a@x"},"unknown":true}""")
    val ex = the[SnapError] thrownBy Config.read(path)
    ex.message should include("unknown field")
  }

  test("read rejects unknown nested fields") {
    val path = tempFile("""{"contributor":{"id":"a@x","extra":1}}""")
    val ex = the[SnapError] thrownBy Config.read(path)
    ex.message should include("unknown field")
  }

  test("read does not itself validate contributor id grammar") {
    val path = tempFile("""{"contributor":{"id":"not-an-id"}}""")
    Config.read(path) shouldBe Some(ContributorConfig("not-an-id"))
  }

  test("write validates the id and rejects an invalid one without writing") {
    val dir = Files.createTempDirectory("snap-config-")
    val path = dir.resolve("config.json")
    a[SnapError] should be thrownBy Config.write(path, "not-an-id")
    Files.exists(path) shouldBe false
  }

  test("write produces the canonical two-field shape regardless of prior content") {
    val path = tempFile("""{"contributor":{"id":"old@x"},"unknown":true}""")
    Config.write(path, "new@x")
    Config.read(path) shouldBe Some(ContributorConfig("new@x"))
    new String(
      Files.readAllBytes(path),
      UTF_8
    ) shouldBe "{\n  \"contributor\": {\n    \"id\": \"new@x\"\n  }\n}\n"
  }

  test("resolveContributorId prefers local over global") {
    val localDir = Files.createTempDirectory("snap-local-")
    val snapDir = Files.createDirectory(localDir.resolve(".snap"))
    Config.write(Config.localPath(snapDir), "local@x")

    val home = Files.createTempDirectory("snap-home-")
    Config.write(Config.globalPath(home), "global@x")

    Config.resolveContributorId(Some(snapDir), Some(home)) shouldBe "local@x"
  }

  test("resolveContributorId falls back to global when local is absent") {
    val snapDir = Files.createTempDirectory("snap-local-")
    val home = Files.createTempDirectory("snap-home-")
    Config.write(Config.globalPath(home), "global@x")

    Config.resolveContributorId(Some(snapDir), Some(home)) shouldBe "global@x"
  }

  test("resolveContributorId does not fall back when local is present but invalid") {
    val localDir = Files.createTempDirectory("snap-local-")
    val snapDir = Files.createDirectory(localDir.resolve(".snap"))
    Files.write(Config.localPath(snapDir), """{"contributor":{"id":"not-an-id"}}""".getBytes(UTF_8))

    val home = Files.createTempDirectory("snap-home-")
    Config.write(Config.globalPath(home), "global@x")

    val ex = the[SnapError] thrownBy Config.resolveContributorId(Some(snapDir), Some(home))
    ex.message should include("invalid contributor id")
  }

  test("resolveContributorId fails with the exact required message when nothing is configured") {
    val snapDir = Files.createTempDirectory("snap-local-")
    val ex = the[SnapError] thrownBy Config.resolveContributorId(Some(snapDir), None)
    ex.message shouldBe "contributor.id is required; configure it locally or globally"
  }
}
