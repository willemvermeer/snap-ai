package snap.workspace

import java.nio.file.Files
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class RepoLocatorSpec extends AnyFunSuite with Matchers with BeforeAndAfterEach {

  test("finds a .snap directory in the starting directory") {
    val root = Files.createTempDirectory("snap-repo-")
    Files.createDirectory(root.resolve(".snap"))
    RepoLocator.locate(root) shouldBe Some(root.resolve(".snap"))
  }

  test("walks up through nested directories to find the nearest repository") {
    val root = Files.createTempDirectory("snap-repo-")
    Files.createDirectory(root.resolve(".snap"))
    val nested = Files.createDirectories(root.resolve("a/b/c"))
    RepoLocator.locate(nested) shouldBe Some(root.resolve(".snap"))
  }

  test("returns None when no repository is found up to the filesystem root") {
    val root = Files.createTempDirectory("snap-no-repo-")
    RepoLocator.locate(root) shouldBe None
  }

  test("finds the nearest repository, not an outer one") {
    val outer = Files.createTempDirectory("snap-outer-")
    Files.createDirectory(outer.resolve(".snap"))
    val inner = Files.createDirectories(outer.resolve("inner"))
    Files.createDirectory(inner.resolve(".snap"))
    RepoLocator.locate(inner) shouldBe Some(inner.resolve(".snap"))
  }
}
