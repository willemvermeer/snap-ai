package snap.workspace

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._
import snap.repository.{Change, EditOp, Patch, Repository}
import snap.version.Version

class RepositoryFileSpec extends AnyFunSuite with Matchers {

  test("write then read round-trips an empty repository") {
    val snapDir = Files.createTempDirectory("snap-repofile-")
    val repository = Repository(Version.Empty, Vector.empty)
    RepositoryFile.write(snapDir, repository)
    RepositoryFile.read(snapDir) shouldBe repository
  }

  test("write then read round-trips a repository with patches") {
    val snapDir = Files.createTempDirectory("snap-repofile-")
    val patch = Patch(
      "a@x",
      1,
      Version.Empty,
      "add greeting",
      Vector(Change.Text("hello.txt", Vector(EditOp.Insert(Vector("hello\n")))))
    )
    val repository = Repository(Version.fromPairs(Seq("a@x" -> 1L)), Vector(patch))
    RepositoryFile.write(snapDir, repository)
    RepositoryFile.read(snapDir) shouldBe repository
  }

  test("write replaces a previously written repository.json rather than appending") {
    val snapDir = Files.createTempDirectory("snap-repofile-")
    RepositoryFile.write(snapDir, Repository(Version.Empty, Vector.empty))
    val patch = Patch("a@x", 1, Version.Empty, "m", Vector(Change.Put("x", Vector(1))))
    val second = Repository(Version.fromPairs(Seq("a@x" -> 1L)), Vector(patch))
    RepositoryFile.write(snapDir, second)
    RepositoryFile.read(snapDir) shouldBe second
  }

  test("write leaves no leftover temp file behind") {
    val snapDir = Files.createTempDirectory("snap-repofile-")
    RepositoryFile.write(snapDir, Repository(Version.Empty, Vector.empty))
    val leftovers = Files.list(snapDir).iterator().asScala.toVector.map(_.getFileName.toString)
    leftovers shouldBe Vector("repository.json")
  }
}
