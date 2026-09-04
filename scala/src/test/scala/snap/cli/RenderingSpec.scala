package snap.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.workspace.PathStatus

/**
 * SPEC.md §7.11's exact byte rules, checked directly against the acceptance suite's
 * own worked values (tests/28-terminal-presentation.yaml).
 */
class RenderingSpec extends AnyFunSuite with Matchers {

  private val Esc: Char = 27.toChar

  test("plain mode never emits an escape byte") {
    Rendering.success(false, "Initialized repository", "()") shouldBe "()\n"
    Rendering.statusHeader(false, "()") shouldBe "version ()\n"
    Rendering.statusClean(false) shouldBe ""
    Rendering.statusRow(false, PathStatus.Added, "f") shouldBe "A f\n"
    Rendering.statusRow(false, PathStatus.Modified, "f") shouldBe "M f\n"
    Rendering.statusRow(false, PathStatus.Deleted, "f") shouldBe "D f\n"
    Rendering.logEntries(false, Vector(("(a@x->1)", "a@x", "first"))) shouldBe
      "(a@x->1)\ta@x\tfirst\n"
    Rendering.version(false, "snap 1.0.0") shouldBe "snap 1.0.0\n"
    Rendering.warning(false, "auto-resolved f: put-wins") shouldBe
      "warning: auto-resolved f: put-wins\n"
    Rendering.error(false, "snap: invalid command or arguments") shouldBe
      "snap: invalid command or arguments\n"
  }

  test("success line matches SPEC.md's exact worked example") {
    Rendering.success(true, "Initialized repository", "()") shouldBe
      s"${Esc}[32m✓${Esc}[0m ${Esc}[1mInitialized repository${Esc}[0m ${Esc}[36m()${Esc}[0m\n"
  }

  test("status header, clean line, and each row's color/symbol/label") {
    Rendering.statusHeader(true, "(alice@x->1)") shouldBe
      s"${Esc}[1mSnap status${Esc}[0m  ${Esc}[36m(alice@x->1)${Esc}[0m\n\n"
    Rendering.statusClean(true) shouldBe s"  ${Esc}[32m✓${Esc}[0m Working tree clean\n"
    Rendering.statusRow(true, PathStatus.Added, "added.txt") shouldBe
      s"  ${Esc}[32m+${Esc}[0m added.txt ${Esc}[2m(added)${Esc}[0m\n"
    Rendering.statusRow(true, PathStatus.Modified, "modified.txt") shouldBe
      s"  ${Esc}[33m~${Esc}[0m modified.txt ${Esc}[2m(modified)${Esc}[0m\n"
    Rendering.statusRow(true, PathStatus.Deleted, "gone.txt") shouldBe
      s"  ${Esc}[31m−${Esc}[0m gone.txt ${Esc}[2m(deleted)${Esc}[0m\n"
  }

  test("log renders one entry with the bullet/by/author styling and no trailing blank line") {
    Rendering.logEntries(true, Vector(("(alice@x->1)", "alice@x", "first"))) shouldBe
      s"${Esc}[36m●${Esc}[0m ${Esc}[1mfirst${Esc}[0m\n" +
      s"  ${Esc}[36m(alice@x->1)${Esc}[0m ${Esc}[2mby${Esc}[0m ${Esc}[35malice@x${Esc}[0m\n"
  }

  test("log separates multiple entries with exactly one blank line between them") {
    val rendered = Rendering.logEntries(
      true,
      Vector(("(alice@x->2)", "alice@x", "second"), ("(alice@x->1)", "alice@x", "first"))
    )
    rendered shouldBe
      s"${Esc}[36m●${Esc}[0m ${Esc}[1msecond${Esc}[0m\n" +
      s"  ${Esc}[36m(alice@x->2)${Esc}[0m ${Esc}[2mby${Esc}[0m ${Esc}[35malice@x${Esc}[0m\n" +
      "\n" +
      s"${Esc}[36m●${Esc}[0m ${Esc}[1mfirst${Esc}[0m\n" +
      s"  ${Esc}[36m(alice@x->1)${Esc}[0m ${Esc}[2mby${Esc}[0m ${Esc}[35malice@x${Esc}[0m\n"
  }

  test("--version wraps the whole string in bold") {
    Rendering.version(true, "snap 1.0.0") shouldBe s"${Esc}[1msnap 1.0.0${Esc}[0m\n"
  }

  test("warning uses the symbol in place of the word and colors the detail") {
    Rendering.warning(true, "auto-resolved same: later-create-wins") shouldBe
      s"${Esc}[33m⚠${Esc}[0m ${Esc}[33mauto-resolved same: later-create-wins${Esc}[0m\n"
  }

  test("error prepends the symbol and wraps the whole line in one red span") {
    Rendering.error(true, "snap: invalid command or arguments") shouldBe
      s"${Esc}[31m✗ snap: invalid command or arguments${Esc}[0m\n"
  }
}
