package snap.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Table-driven coverage of SPEC.md §7.11's SNAP_COLOR / NO_COLOR precedence table. */
class PresentationSpec extends AnyFunSuite with Matchers {

  private def vars(pairs: (String, String)*): Map[String, String] = Map(pairs: _*)

  test("unset SNAP_COLOR resolves to terminal mode per stream when that stream is a TTY") {
    Presentation.resolve(vars(), stdoutIsTty = true, stderrIsTty = false) shouldBe
      Right(Presentation(stdout = true, stderr = false))
    Presentation.resolve(vars(), stdoutIsTty = false, stderrIsTty = true) shouldBe
      Right(Presentation(stdout = false, stderr = true))
  }

  test("SNAP_COLOR=auto behaves the same as unset") {
    Presentation.resolve(
      vars("SNAP_COLOR" -> "auto"),
      stdoutIsTty = true,
      stderrIsTty = true
    ) shouldBe
      Right(Presentation(stdout = true, stderr = true))
  }

  test("NO_COLOR forces plain in auto mode regardless of TTY, even when empty") {
    Presentation.resolve(vars("NO_COLOR" -> ""), stdoutIsTty = true, stderrIsTty = true) shouldBe
      Right(Presentation(stdout = false, stderr = false))
    Presentation.resolve(vars("NO_COLOR" -> "1"), stdoutIsTty = true, stderrIsTty = true) shouldBe
      Right(Presentation(stdout = false, stderr = false))
  }

  test("SNAP_COLOR=always overrides NO_COLOR and ignores TTY-ness") {
    Presentation.resolve(
      vars("SNAP_COLOR" -> "always", "NO_COLOR" -> "1"),
      stdoutIsTty = false,
      stderrIsTty = false
    ) shouldBe
      Right(Presentation(stdout = true, stderr = true))
  }

  test("SNAP_COLOR=never is plain on both streams regardless of TTY") {
    Presentation.resolve(
      vars("SNAP_COLOR" -> "never"),
      stdoutIsTty = true,
      stderrIsTty = true
    ) shouldBe
      Right(Presentation(stdout = false, stderr = false))
  }

  test("any other SNAP_COLOR value is a validation error") {
    Presentation.resolve(
      vars("SNAP_COLOR" -> "yes"),
      stdoutIsTty = false,
      stderrIsTty = false
    ) shouldBe
      Left("SNAP_COLOR must be auto, always, or never")
    Presentation.resolve(
      vars("SNAP_COLOR" -> ""),
      stdoutIsTty = false,
      stderrIsTty = false
    ) shouldBe
      Left("SNAP_COLOR must be auto, always, or never")
  }
}
