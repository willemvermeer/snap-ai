package snap.workspace

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class TreeInstallerSpec extends AnyFunSuite with Matchers {

  private def utf8(s: String): Vector[Byte] = s.getBytes(UTF_8).toVector

  test("writes new files and creates missing parent directories") {
    val root = Files.createTempDirectory("snap-installer-")
    TreeInstaller.install(root, Map.empty, Map("a/b/c" -> utf8("hi")))
    new String(Files.readAllBytes(root.resolve("a/b/c")), UTF_8) shouldBe "hi"
  }

  test("deletes files no longer present in the target") {
    val root = Files.createTempDirectory("snap-installer-")
    Files.write(root.resolve("gone"), "x".getBytes(UTF_8))
    TreeInstaller.install(root, Map("gone" -> utf8("x")), Map.empty)
    Files.exists(root.resolve("gone")) shouldBe false
  }

  test("overwrites changed file content") {
    val root = Files.createTempDirectory("snap-installer-")
    Files.write(root.resolve("f"), "old".getBytes(UTF_8))
    TreeInstaller.install(root, Map("f" -> utf8("old")), Map("f" -> utf8("new")))
    new String(Files.readAllBytes(root.resolve("f")), UTF_8) shouldBe "new"
  }

  test("swaps a file for a directory at the same name, pruning the now-empty directory first") {
    val root = Files.createTempDirectory("snap-installer-")
    Files.createDirectories(root.resolve("node"))
    Files.write(root.resolve("node/child"), "child".getBytes(UTF_8))
    TreeInstaller.install(root, Map("node/child" -> utf8("child")), Map("node" -> utf8("file")))
    Files.isRegularFile(root.resolve("node")) shouldBe true
    Files.exists(root.resolve("node/child")) shouldBe false
    new String(Files.readAllBytes(root.resolve("node")), UTF_8) shouldBe "file"
  }

  test("swaps a directory for a file at the same name") {
    val root = Files.createTempDirectory("snap-installer-")
    Files.write(root.resolve("node"), "file".getBytes(UTF_8))
    TreeInstaller.install(root, Map("node" -> utf8("file")), Map("node/child" -> utf8("child")))
    Files.isDirectory(root.resolve("node")) shouldBe true
    new String(Files.readAllBytes(root.resolve("node/child")), UTF_8) shouldBe "child"
  }

  test("never touches .snap") {
    val root = Files.createTempDirectory("snap-installer-")
    Files.createDirectories(root.resolve(".snap"))
    Files.write(root.resolve(".snap/repository.json"), "{}".getBytes(UTF_8))
    TreeInstaller.install(root, Map.empty, Map.empty)
    Files.exists(root.resolve(".snap/repository.json")) shouldBe true
  }

  test("does not rewrite a file whose content is already correct") {
    val root = Files.createTempDirectory("snap-installer-")
    val filePath = root.resolve("f")
    Files.write(filePath, "same".getBytes(UTF_8))
    val before = Files.getLastModifiedTime(filePath)
    Thread.sleep(5)
    TreeInstaller.install(root, Map("f" -> utf8("same")), Map("f" -> utf8("same")))
    Files.getLastModifiedTime(filePath) shouldBe before
  }
}
