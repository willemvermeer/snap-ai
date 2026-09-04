package snap.cli

import snap.diff.{TextDiff, TextTokens}
import snap.repository.Change

/**
 * SPEC.md §7.5's text/put/delete decision, shared by `commit` and `revert` (both author
 * a patch from an old/new byte comparison): "Uses a text change when the new content is
 * text and the old path is absent or text. Otherwise it uses put; removed paths use
 * delete."
 */
private[cli] object ChangeBuilder {
  def build(path: String, oldBytes: Option[Vector[Byte]], newBytes: Option[Vector[Byte]]): Change =
    newBytes match {
      case None => Change.Delete(path)
      case Some(nb) =>
        val newIsText = TextTokens.isText(nb.toArray)
        val oldIsTextOrAbsent = oldBytes.forall(b => TextTokens.isText(b.toArray))
        if (newIsText && oldIsTextOrAbsent) {
          val oldTokens = oldBytes
            .map(b => TextTokens.tokenize(TextTokens.toText(b.toArray)))
            .getOrElse(Vector.empty)
          val newTokens = TextTokens.tokenize(TextTokens.toText(nb.toArray))
          Change.Text(path, TextDiff.diff(oldTokens, newTokens))
        } else {
          Change.Put(path, nb)
        }
    }
}
