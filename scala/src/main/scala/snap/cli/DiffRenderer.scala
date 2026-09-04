package snap.cli

import snap.diff.{TextDiff, TextTokens}
import snap.repository.EditOp

/**
 * SPEC.md §7.6's exact diff output: a whole-file unified-style block for a text path,
 * or one "Binary files ... differ" line otherwise, with `/dev/null` substituted for an
 * absent side.
 */
object DiffRenderer {

  private val Esc: Char = 27.toChar

  private def styledLine(terminal: Boolean, code: Int, content: String): String =
    if (terminal) s"$Esc[${code}m$content${Esc}[0m\n" else s"$content\n"

  def render(
    path: String,
    oldBytes: Option[Vector[Byte]],
    newBytes: Option[Vector[Byte]],
    terminal: Boolean
  ): String = {
    val oldIsText = oldBytes.forall(b => TextTokens.isText(b.toArray))
    val newIsText = newBytes.forall(b => TextTokens.isText(b.toArray))
    if (oldIsText && newIsText) textBlock(path, oldBytes, newBytes, terminal)
    else binaryLine(path, oldBytes, newBytes, terminal)
  }

  private def textBlock(
    path: String,
    oldBytes: Option[Vector[Byte]],
    newBytes: Option[Vector[Byte]],
    terminal: Boolean
  ): String = {
    val oldTokens =
      oldBytes.map(b => TextTokens.tokenize(TextTokens.toText(b.toArray))).getOrElse(Vector.empty)
    val newTokens =
      newBytes.map(b => TextTokens.tokenize(TextTokens.toText(b.toArray))).getOrElse(Vector.empty)

    val sb = new StringBuilder
    sb.append(styledLine(terminal, 1, s"--- ${header("a", path, oldBytes.isDefined)}"))
    sb.append(styledLine(terminal, 1, s"+++ ${header("b", path, newBytes.isDefined)}"))
    sb.append(styledLine(terminal, 36, s"@@ -1,${oldTokens.length} +1,${newTokens.length} @@"))

    var oi = 0
    TextDiff.diff(oldTokens, newTokens).foreach {
      case EditOp.Retain(n) =>
        (0 until n.toInt).foreach { _ =>
          sb.append(tokenLine(terminal, ' ', None, oldTokens(oi))); oi += 1
        }
      case EditOp.Delete(n) =>
        (0 until n.toInt).foreach { _ =>
          sb.append(tokenLine(terminal, '-', Some(31), oldTokens(oi))); oi += 1
        }
      case EditOp.Insert(tokens) =>
        tokens.foreach(t => sb.append(tokenLine(terminal, '+', Some(32), t)))
    }
    sb.toString()
  }

  /**
   * A retained/deleted/inserted token already carries its own trailing LF unless it's
   * the file's final token; SPEC.md §7.6 requires that missing LF be supplied so the
   * output stays one line per token, followed by the "no newline" marker line (styled
   * dim, per §7.11, regardless of the token's own prefix/color).
   */
  private def tokenLine(terminal: Boolean, prefix: Char, code: Option[Int], token: String): String =
    if (token.endsWith("\n")) {
      val content = s"$prefix${token.dropRight(1)}"
      code.fold(s"$content\n")(c => styledLine(terminal, c, content))
    } else {
      val firstLine = code.fold(s"$prefix$token\n")(c => styledLine(terminal, c, s"$prefix$token"))
      firstLine + styledLine(terminal, 2, "\\ No newline at end of file")
    }

  private def binaryLine(
    path: String,
    oldBytes: Option[Vector[Byte]],
    newBytes: Option[Vector[Byte]],
    terminal: Boolean
  ): String =
    styledLine(
      terminal,
      33,
      s"Binary files ${header("a", path, oldBytes.isDefined)} and ${header("b", path, newBytes.isDefined)} differ"
    )

  private def header(side: String, path: String, present: Boolean): String =
    if (present) s"$side/$path" else "/dev/null"
}
