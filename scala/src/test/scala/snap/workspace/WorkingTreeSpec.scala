package snap.workspace

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.SnapError

class WorkingTreeSpec extends AnyFunSuite with Matchers {

  private def newRoot(): java.nio.file.Path = Files.createTempDirectory("snap-wt-")

  test("scan reads regular files, including nested ones, and skips .snap entirely") {
    val root = newRoot()
    Files.write(root.resolve("a.txt"), "a\n".getBytes(UTF_8))
    Files.createDirectories(root.resolve("sub"))
    Files.write(root.resolve("sub/b.txt"), "b\n".getBytes(UTF_8))
    Files.createDirectories(root.resolve(".snap"))
    Files.write(root.resolve(".snap/repository.json"), "{}".getBytes(UTF_8))

    WorkingTree.scan(root) shouldBe Map(
      "a.txt" -> "a\n".getBytes(UTF_8).toVector,
      "sub/b.txt" -> "b\n".getBytes(UTF_8).toVector
    )
  }

  test("scan on a repository with only .snap produces an empty tree") {
    val root = newRoot()
    Files.createDirectories(root.resolve(".snap"))
    WorkingTree.scan(root) shouldBe Map.empty
  }

  test("scan does not track empty directories") {
    val root = newRoot()
    Files.createDirectories(root.resolve("empty-dir"))
    WorkingTree.scan(root) shouldBe Map.empty
  }

  test("scan rejects a symlink, even a dangling one, without following it") {
    val root = newRoot()
    Files.createSymbolicLink(root.resolve("link"), root.resolve("missing-target"))
    val ex = the[SnapError] thrownBy WorkingTree.scan(root)
    ex.message shouldBe "unsupported working tree entry: link"
  }

  test("scan rejects a symlink nested in a subdirectory, reporting its relative path") {
    val root = newRoot()
    Files.createDirectories(root.resolve("sub"))
    Files.createSymbolicLink(root.resolve("sub/link"), root.resolve("elsewhere"))
    val ex = the[SnapError] thrownBy WorkingTree.scan(root)
    ex.message shouldBe "unsupported working tree entry: sub/link"
  }

  test("scan reads byte-exact binary content") {
    val root = newRoot()
    val bytes: Array[Byte] = Array(0, 1, -1, 127, -128)
    Files.write(root.resolve("data.bin"), bytes)
    WorkingTree.scan(root) shouldBe Map("data.bin" -> bytes.toVector)
  }
}
