package snap.replay

import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import snap.diff.{OperationalTransform, TextDiff, TextTokens}
import snap.path.TrackedPath
import snap.repository.{Change, Patch}
import snap.version.Version

/**
 * SPEC.md §6's deterministic replay: patch selection/integration order (§6.1),
 * namespace-conflict resolution and per-path evaluation (§6.2), the text OT transform
 * (§6.3), and the path-level winner rules with warnings (§6.4).
 *
 * `snap.replay.TreeMaterializer` (plan unit 5) applies each patch's change directly
 * against the running tree, which is only correct for a non-concurrent (purely
 * sequential) history; this engine is the general algorithm it was always meant to be
 * replaced by, so `TreeMaterializer` now delegates here.
 */
object ReplayEngine {

  type Tree = Map[String, Vector[Byte]]
  type Warning = (String, String)

  final case class Result(tree: Tree, warnings: Vector[Warning])

  private val warningOrdering: Ordering[Warning] =
    Ordering.by[Warning, String](_._1)(TrackedPath.ordering).orElseBy(_._2)

  /**
   * `orderedPatches` must already be exactly one version's causal closure, in
   * canonical integration order (e.g. [[VersionResolution.resolve]]'s result). Every
   * patch's own base then resolves to a sub-closure that, by the closure property, is
   * itself entirely contained within `orderedPatches` — so that same vector doubles as
   * the universe recursive base-resolution searches.
   */
  def replay(orderedPatches: Vector[Patch]): Result = {
    val cache = mutable.Map.empty[Version, (Tree, Set[Warning])]
    val (tree, warnings) = integrateSequence(orderedPatches, orderedPatches, cache)
    Result(tree, warnings.toVector.sorted(warningOrdering))
  }

  private def materializeVersion(
    version: Version,
    allPatches: Vector[Patch],
    cache: mutable.Map[Version, (Tree, Set[Warning])]
  ): (Tree, Set[Warning]) =
    cache.getOrElseUpdate(
      version, {
        val closureOrdered = VersionResolution
          .resolve(version, allPatches)
          .getOrElse(
            throw new IllegalStateException(
              s"replay: base version ${version.toCanonicalString} is not known"
            )
          )
        integrateSequence(closureOrdered, allPatches, cache)
      }
    )

  private def integrateSequence(
    ordered: Vector[Patch],
    allPatches: Vector[Patch],
    cache: mutable.Map[Version, (Tree, Set[Warning])]
  ): (Tree, Set[Warning]) =
    ordered.foldLeft((Map.empty[String, Vector[Byte]], Set.empty[Warning])) {
      case ((tree, warnings), patch) =>
        val (baseTree, baseWarnings) = materializeVersion(patch.base, allPatches, cache)
        val (newTree, stepWarnings) = integrateOnePatch(baseTree, tree, patch)
        (newTree, warnings ++ baseWarnings ++ stepWarnings)
    }

  /**
   * SPEC.md §6.2: for incoming patch `P`, `base` is its exact base tree, `current` is
   * the canonical tree built so far ("it contains base plus only earlier concurrent
   * effects").
   */
  private def integrateOnePatch(base: Tree, current: Tree, patch: Patch): (Tree, Set[Warning]) = {
    val changeByPath = patch.changes.map(c => c.path -> c).toMap
    val presentPaths = patch.changes.collect {
      case c: Change.Text => c.path
      case c: Change.Put => c.path
    }.toSet
    val deletedPaths = patch.changes.collect { case c: Change.Delete => c.path }.toSet
    val currentAfterOwnDeletes = current -- deletedPaths

    var warnings = Set.empty[Warning]
    var namespaceInstalls = Map.empty[String, Vector[Byte]]
    var namespaceRemovals = Set.empty[String]
    var namespaceSettled = Set.empty[String]

    // Namespace conflicts, whole-patch level: "Let S be the paths that P makes
    // present, and let C' be C with every path that P authored as a deletion removed.
    // If a path in S has a different current ancestor or descendant in C', mark the
    // incoming path for installation ... and mark every conflicting current path for
    // removal."
    presentPaths.foreach { p =>
      val conflicts = currentAfterOwnDeletes.keySet.filter { q =>
        q != p && (TrackedPath.isSegmentPrefixOf(q, p) || TrackedPath.isSegmentPrefixOf(p, q))
      }
      if (conflicts.nonEmpty) {
        val authored = authoredResult(base, changeByPath(p))
          .getOrElse(
            throw new IllegalStateException(s"namespace-conflicting path $p has no authored result")
          )
        namespaceInstalls += p -> authored
        namespaceSettled += p
        conflicts.foreach { q =>
          namespaceRemovals += q
          warnings += q -> "namespace-wins"
        }
      }
    }

    // Per-path evaluation, §6.2 rules 1-4, for changes the namespace rule didn't settle.
    var pathResults = Map.empty[String, Option[Vector[Byte]]]
    patch.changes.foreach { change =>
      val p = change.path
      if (!namespaceSettled(p)) {
        val authored = authoredResult(base, change)
        val (result, warning) = resolvePath(base.get(p), current.get(p), authored, change)
        pathResults += p -> result
        warning.foreach(w => warnings += p -> w)
      }
    }

    var updated = current
    namespaceRemovals.foreach(q => updated -= q)
    namespaceInstalls.foreach { case (p, bytes) => updated += p -> bytes }
    pathResults.foreach {
      case (p, Some(bytes)) => updated += p -> bytes
      case (p, None) => updated -= p
    }

    (updated, warnings)
  }

  /**
   * The authored result `T` of applying `change` to `base` — independent of any
   * concurrent effects, purely "what would this path look like if `change` were the
   * only thing that happened."
   */
  private def authoredResult(base: Tree, change: Change): Option[Vector[Byte]] = change match {
    case _: Change.Delete => None
    case Change.Put(_, content) => Some(content)
    case Change.Text(path, edit) =>
      val oldTokens = base.get(path).map(tokenizeBytes).getOrElse(Vector.empty)
      Some(joinTokens(TextDiff.applyScript(oldTokens, edit)))
  }

  /**
   * SPEC.md §6.2's per-path evaluation against the same `B`/`C`: rules 1-3, falling
   * back to §6.4's path-level rules (rule 4, "otherwise").
   */
  private def resolvePath(
    basePath: Option[Vector[Byte]],
    currentPath: Option[Vector[Byte]],
    authored: Option[Vector[Byte]],
    change: Change
  ): (Option[Vector[Byte]], Option[String]) =
    if (basePath == currentPath) {
      (authored, None) // rule 1: apply the authored change directly
    } else if (currentPath == authored) {
      (currentPath, None) // rule 2: identical concurrent changes collapse, no warning
    } else if (
      isText(basePath) && isText(currentPath) && isText(authored) && change
        .isInstanceOf[Change.Text]
    ) {
      // rule 3: derive Q = diff(B, C), transform P through Q (§6.3), apply to C.
      val baseTokens = tokenizeOpt(basePath)
      val currentTokens = tokenizeOpt(currentPath)
      val contextEdit = TextDiff.diff(baseTokens, currentTokens)
      val incomingEdit = change.asInstanceOf[Change.Text].edit
      val transformed = OperationalTransform.transform(incomingEdit, contextEdit)
      (Some(joinTokens(TextDiff.applyScript(currentTokens, transformed))), None)
    } else {
      pathLevelRule(basePath, currentPath, authored, change) // rule 4: SPEC.md §6.4
    }

  /**
   * SPEC.md §6.4. Reached only once §6.2's rules 1-3 have already been ruled out, so
   * `currentPath != authored` and `authored` is always defined here — §6.4's own rule 1
   * ("if C and T are identical") can never actually fire through this path, but is kept
   * for a reader matching this against the spec's numbered list one-to-one.
   */
  private def pathLevelRule(
    basePath: Option[Vector[Byte]],
    currentPath: Option[Vector[Byte]],
    authored: Option[Vector[Byte]],
    change: Change
  ): (Option[Vector[Byte]], Option[String]) =
    if (currentPath == authored) {
      (currentPath, None) // rule 1
    } else if (authored.isEmpty) {
      (None, Some("delete-wins")) // rule 2: the incoming delete wins
    } else if (basePath.isDefined && currentPath.isEmpty) {
      (None, Some("delete-wins")) // rule 3: the earlier concurrent delete wins
    } else if (basePath.isEmpty && currentPath.isDefined) {
      (authored, Some("later-create-wins")) // rule 4: the incoming (later) create wins
    } else if (change.isInstanceOf[Change.Put]) {
      (authored, Some("later-put-wins")) // rule 5: the incoming atomic replacement wins
    } else {
      (currentPath, Some("put-wins")) // rule 6: incompatible current (non-text) content wins
    }

  private def isText(bytes: Option[Vector[Byte]]): Boolean =
    bytes.exists(b => TextTokens.isText(b.toArray))
  private def tokenizeBytes(bytes: Vector[Byte]): Vector[String] =
    TextTokens.tokenize(TextTokens.toText(bytes.toArray))
  private def tokenizeOpt(bytes: Option[Vector[Byte]]): Vector[String] =
    bytes.map(tokenizeBytes).getOrElse(Vector.empty)
  private def joinTokens(tokens: Vector[String]): Vector[Byte] =
    tokens.mkString.getBytes(UTF_8).toVector
}
