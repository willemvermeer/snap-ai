package snap.cli

/**
 * Which streams render terminal mode (SPEC.md §7.11). Actual ANSI rendering is future
 * work (plan unit 10); this only resolves and validates the mode selection so the
 * `SNAP_COLOR` error contract is correct from the start.
 */
final case class Presentation(stdout: Boolean, stderr: Boolean)

object Presentation {

  /**
   * `stdoutIsTty` / `stderrIsTty` are injected so `auto` selection is independently
   * testable per stream, as SPEC.md §11 requires ("unit-test `auto` selection for TTY
   * and non-TTY stdout and stderr independently").
   */
  def resolve(
    vars: Map[String, String],
    stdoutIsTty: Boolean,
    stderrIsTty: Boolean
  ): Either[String, Presentation] =
    vars.get("SNAP_COLOR") match {
      case None | Some("auto") =>
        if (vars.contains("NO_COLOR")) Right(Presentation(stdout = false, stderr = false))
        else Right(Presentation(stdout = stdoutIsTty, stderr = stderrIsTty))
      case Some("always") => Right(Presentation(stdout = true, stderr = true))
      case Some("never") => Right(Presentation(stdout = false, stderr = false))
      case Some(_) => Left("SNAP_COLOR must be auto, always, or never")
    }
}
