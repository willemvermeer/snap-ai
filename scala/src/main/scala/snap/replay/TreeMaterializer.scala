package snap.replay

import java.nio.charset.StandardCharsets.UTF_8
import snap.diff.{TextDiff, TextTokens}
import snap.repository.{Change, Patch, Repository, RepositoryValidator}

/**
 * Materializes a repository's current tree: the path/byte map produced by integrating
 * every patch in `repository.patches`, in SPEC.md §6.1's canonical order.
 *
 * This applies each patch's changes directly to the tree built so far — §6.2 rule 1,
 * "If the path is identical in B and C, apply the authored change directly" — which is
 * exactly correct whenever there is no concurrent history to reconcile. That covers
 * every repository this project's own commands can produce before `merge` exists (plan
 * unit 8): a `Repository` that only ever grew through sequential `commit`s has, by
 * construction, each patch's base equal to the exact prior frontier, so B always equals
 * C at every step. The general algorithm — OT (§6.3) and the §6.4 conflict rules for
 * genuine concurrent integration, needed once a repository can contain patches from an
 * actual `merge` or a hand-authored concurrent history — is plan unit 7's replay engine,
 * expected to replace this function's body without changing its signature or callers.
 */
object TreeMaterializer {

  def materialize(repository: Repository): Map[String, Vector[Byte]] = {
    val ordered = RepositoryValidator.integrationOrder(repository.patches)
    ordered.foldLeft(Map.empty[String, Vector[Byte]])(integrate)
  }

  private def integrate(tree: Map[String, Vector[Byte]], patch: Patch): Map[String, Vector[Byte]] =
    patch.changes.foldLeft(tree) { (t, change) =>
      change match {
        case Change.Delete(path) => t - path
        case Change.Put(path, content) => t + (path -> content)
        case Change.Text(path, edit) =>
          val oldTokens = t.get(path) match {
            case Some(bytes) => TextTokens.tokenize(TextTokens.toText(bytes.toArray))
            case None => Vector.empty
          }
          val newTokens = TextDiff.applyScript(oldTokens, edit)
          val newBytes = newTokens.mkString.getBytes(UTF_8).toVector
          t + (path -> newBytes)
      }
    }
}
