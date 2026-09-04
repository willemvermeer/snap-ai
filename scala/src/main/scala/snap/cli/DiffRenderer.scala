package snap.cli

import snap.diff.{TextDiff, TextTokens}
import snap.repository.EditOp

/**
 * SPEC.md §7.6's exact diff output: a whole-file unified-style block for a text path,
 * or one "Binary files ... differ" line otherwise, with `/dev/null` substituted for an
 * absent side.
 */
object DiffRenderer {

  def render(
    path: String,
    oldBytes: Option[Vector[Byte]],
    newBytes: Option[Vector[Byte]]
  ): String = {
    val oldIsText = oldBytes.forall(b => TextTokens.isText(b.toArray))
    val newIsText = newBytes.forall(b => TextTokens.isText(b.toArray))
    if (oldIsText && newIsText) textBlock(path, oldBytes, newBytes)
    else binaryLine(path, oldBytes, newBytes)
  }

  private def textBlock(
    path: String,
    oldBytes: Option[Vector[Byte]],
    newBytes: Option[Vector[Byte]]
  ): String = {
    val oldTokens =
      oldBytes.map(b => TextTokens.tokenize(TextTokens.toText(b.toArray))).getOrElse(Vector.empty)
    val newTokens =
      newBytes.map(b => TextTokens.tokenize(TextTokens.toText(b.toArray))).getOrElse(Vector.empty)

    val sb = new StringBuilder
    sb.append(s"--- ${header("a", path, oldBytes.isDefined)}\n")
    sb.append(s"+++ ${header("b", path, newBytes.isDefined)}\n")
    sb.append(s"@@ -1,${oldTokens.length} +1,${newTokens.length} @@\n")

    var oi = 0
    TextDiff.diff(oldTokens, newTokens).foreach {
      case EditOp.Retain(n) =>
        (0 until n.toInt).foreach { _ => sb.append(tokenLine(' ', oldTokens(oi))); oi += 1 }
      case EditOp.Delete(n) =>
        (0 until n.toInt).foreach { _ => sb.append(tokenLine('-', oldTokens(oi))); oi += 1 }
      case EditOp.Insert(tokens) =>
        tokens.foreach(t => sb.append(tokenLine('+', t)))
    }
    sb.toString()
  }

  /**
   * A retained/deleted/inserted token already carries its own trailing LF unless it's
   * the file's final token; SPEC.md §7.6 requires that missing LF be supplied so the
   * output stays one line per token, followed by the "no newline" marker line.
   */
  private def tokenLine(prefix: Char, token: String): String =
    if (token.endsWith("\n")) s"$prefix$token" else s"$prefix$token\n\\ No newline at end of file\n"

  private def binaryLine(
    path: String,
    oldBytes: Option[Vector[Byte]],
    newBytes: Option[Vector[Byte]]
  ): String =
    s"Binary files ${header("a", path, oldBytes.isDefined)} and ${header("b", path, newBytes.isDefined)} differ\n"

  private def header(side: String, path: String, present: Boolean): String =
    if (present) s"$side/$path" else "/dev/null"
}
