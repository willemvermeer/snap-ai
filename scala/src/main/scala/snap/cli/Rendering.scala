package snap.cli

import snap.workspace.PathStatus

/**
 * SPEC.md §7.11's two output presentations. Plain mode is the byte-stable interface
 * specified by §§6.4, 7.1-7.10, and 10; terminal mode adds color, symbols, and spacing
 * for interactive use without changing execution, effects, warning selection/order, or
 * exit status. Each function here takes the resolved `terminal: Boolean` for the one
 * stream it writes to (from `Cli.Env.presentation`), so a caller that never looks at
 * presentation still gets the correct plain byte-stable output by construction.
 */
object Rendering {

  // Built from its ordinal rather than a source-level escape sequence: guarantees a
  // real ASCII ESC byte in the compiled output regardless of how an editor or tool
  // might normalize an escape sequence written directly into source.
  private val Esc: Char = 27.toChar

  private def sgr(code: Int, text: String): String = s"$Esc[${code}m$text${Esc}[0m"

  /** init/commit/revert/merge's shared success line. */
  def success(terminal: Boolean, label: String, version: String): String =
    if (terminal) s"${sgr(32, "✓")} ${sgr(1, label)} ${sgr(36, version)}\n" else s"$version\n"

  def statusHeader(terminal: Boolean, version: String): String =
    if (terminal) s"${sgr(1, "Snap status")}  ${sgr(36, version)}\n\n" else s"version $version\n"

  def statusClean(terminal: Boolean): String =
    if (terminal) s"  ${sgr(32, "✓")} Working tree clean\n" else ""

  def statusRow(terminal: Boolean, status: PathStatus, path: String): String =
    if (terminal) {
      val (color, symbol, label) = status match {
        case PathStatus.Added => (32, "+", "added")
        case PathStatus.Modified => (33, "~", "modified")
        case PathStatus.Deleted => (31, "−", "deleted")
      }
      s"  ${sgr(color, symbol)} $path ${sgr(2, s"($label)")}\n"
    } else {
      val code = status match {
        case PathStatus.Added => "A"
        case PathStatus.Modified => "M"
        case PathStatus.Deleted => "D"
      }
      s"$code $path\n"
    }

  /**
   * One `log` entry per `(version, author, escapedMessage)` triple, already in the
   * reverse canonical order the command prints them in.
   */
  def logEntries(terminal: Boolean, entries: Vector[(String, String, String)]): String =
    if (terminal) {
      entries
        .map { case (version, author, message) =>
          s"${sgr(36, "●")} ${sgr(1, message)}\n  ${sgr(36, version)} ${sgr(2, "by")} ${sgr(35, author)}\n"
        }
        .mkString("\n")
    } else {
      entries.map { case (version, author, message) => s"$version\t$author\t$message\n" }.mkString
    }

  def version(terminal: Boolean, text: String): String =
    if (terminal) s"${sgr(1, text)}\n" else s"$text\n"

  /** A plain `warning: <detail>` line, or its terminal-mode equivalent. */
  def warning(terminal: Boolean, detail: String): String =
    if (terminal) s"${sgr(33, "⚠")} ${sgr(33, detail)}\n" else s"warning: $detail\n"

  /**
   * A plain `<message>` error line (already including any `snap: ` prefix the caller
   * wants), or its terminal-mode equivalent.
   */
  def error(terminal: Boolean, message: String): String =
    if (terminal) s"${sgr(31, s"✗ $message")}\n" else s"$message\n"
}
