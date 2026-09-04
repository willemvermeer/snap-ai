package snap.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class DiffRendererSpec extends AnyFunSuite with Matchers {

  private def utf8(s: String): Vector[Byte] = s.getBytes("UTF-8").toVector

  test("renders a whole-file unified block for a text change") {
    DiffRenderer.render("f", Some(utf8("a\nb\n")), Some(utf8("a\nc\n"))) shouldBe
      "--- a/f\n+++ b/f\n@@ -1,2 +1,2 @@\n a\n-b\n+c\n"
  }

  test("uses /dev/null for an absent old side when creating a text file") {
    DiffRenderer.render("f", None, Some(utf8("new"))) shouldBe
      "--- /dev/null\n+++ b/f\n@@ -1,0 +1,1 @@\n+new\n\\ No newline at end of file\n"
  }

  test("uses /dev/null for an absent new side when deleting a text file") {
    DiffRenderer.render("f", Some(utf8("bye\n")), None) shouldBe
      "--- a/f\n+++ /dev/null\n@@ -1,1 +1,0 @@\n-bye\n"
  }

  test("renders no header content differently for two empty files (no body lines)") {
    DiffRenderer.render("f", None, Some(Vector.empty)) shouldBe
      "--- /dev/null\n+++ b/f\n@@ -1,0 +1,0 @@\n"
  }

  test(
    "renders a binary line when either side is binary, substituting /dev/null for an absent side"
  ) {
    val binary = Vector[Byte](0, 1, 2)
    DiffRenderer.render("f", None, Some(binary)) shouldBe "Binary files /dev/null and b/f differ\n"
    DiffRenderer.render("f", Some(binary), None) shouldBe "Binary files a/f and /dev/null differ\n"
    DiffRenderer.render(
      "f",
      Some(binary),
      Some(utf8("text"))
    ) shouldBe "Binary files a/f and b/f differ\n"
  }

  test("marks a missing trailing newline with the standard marker line") {
    DiffRenderer.render("f", Some(utf8("a\n")), Some(utf8("a"))) shouldBe
      "--- a/f\n+++ b/f\n@@ -1,1 +1,1 @@\n-a\n+a\n\\ No newline at end of file\n"
  }
}
