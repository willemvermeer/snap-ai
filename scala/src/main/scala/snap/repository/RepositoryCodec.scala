package snap.repository

import java.util.Base64
import snap.SnapError
import snap.id.ContributorId
import snap.json.Json
import snap.path.TrackedPath
import snap.version.Version

/**
 * JSON <-> typed `Repository` conversion, plus SPEC.md §4.5's schema-level validation
 * (step 1: "its exact schema and all versions, IDs, paths, messages, and changes").
 * Cross-patch validation (sort order, base closure, acyclicity — §4.5 steps 2-4) is
 * `RepositoryValidator`'s job, run at the end of `decode`. Replay-dependent validation
 * (§4.5 steps 5-6 — a change against its materialized base, and full frontier replay)
 * needs the replay engine and is out of scope here.
 */
object RepositoryCodec {

  def decode(json: Json): Repository = json match {
    case obj: Json.Obj =>
      requireOnlyFields(obj, Set("format", "frontier", "patches"), "repository")
      val fields = obj.fields.toMap
      fields.get("format") match {
        case Some(Json.Num("1", true)) => ()
        case _ => throw SnapError("repository format must be 1")
      }
      val frontier = decodeVersion(field(fields, "frontier", "repository"), "frontier")
      val patchesJson = requireArray(field(fields, "patches", "repository"), "patches")
      val patches = patchesJson.map(decodePatch)
      RepositoryValidator.validate(frontier, patches)
    case _ => throw SnapError("repository must be an object")
  }

  def encode(repository: Repository): Json =
    Json.Obj(
      Vector(
        "format" -> Json.Num("1", isIntegral = true),
        "frontier" -> encodeVersion(repository.frontier),
        "patches" -> Json.Arr(repository.patches.map(encodePatch))
      )
    )

  // ---- decoding -----------------------------------------------------------------------

  private val PatchFields = Set("author", "revision", "base", "message", "changes")

  private def decodePatch(json: Json): Patch = json match {
    case obj: Json.Obj =>
      requireOnlyFields(obj, PatchFields, "patch")
      val fields = obj.fields.toMap
      val author = requireString(field(fields, "author", "patch"), "patch author")
      if (!ContributorId.isValid(author)) throw SnapError(s"invalid contributor id: $author")
      val revision =
        requirePositiveSafeInteger(field(fields, "revision", "patch"), "patch revision")
      val base = decodeVersion(field(fields, "base", "patch"), "base")
      val message = requireString(field(fields, "message", "patch"), "patch message")
      Patch.validateMessage(message)
      val changesJson = requireArray(field(fields, "changes", "patch"), "patch changes")
      if (changesJson.isEmpty) throw SnapError("patch changes is empty")
      val changes = changesJson.map(decodeChange)
      checkChangesSortedAndUnique(changes)
      checkPrefixFree(changes)
      if (revision != base.revisionOf(author) + 1) {
        throw SnapError(s"patch $author revision $revision does not follow its base")
      }
      Patch(author, revision, base, message, changes)
    case _ => throw SnapError("patch must be an object")
  }

  private def checkChangesSortedAndUnique(changes: Vector[Change]): Unit = {
    val paths = changes.map(_.path)
    paths.sliding(2).foreach {
      case Vector(a, b) =>
        if (a == b) throw SnapError(s"patch changes contain a duplicate path: $a")
        if (TrackedPath.ordering.gt(a, b)) throw SnapError("patch changes are not sorted by path")
      case _ => ()
    }
  }

  /**
   * A `text`/`put` change always leaves its path present afterward (only `delete`
   * leaves it absent), regardless of the patch's base — so two such paths within one
   * patch's own changes that stand in an ancestor/descendant segment relationship are
   * always invalid (SPEC.md §2's "prefix-free by path segment", "validated for every
   * patch's authored result"), independent of what the base tree looks like elsewhere.
   * The general case — a conflict against the *base* tree, or against another patch's
   * concurrent effect — needs a materialized tree and is §6.2/§6.4's job (unit 7).
   */
  private def checkPrefixFree(changes: Vector[Change]): Unit = {
    val presentPaths = changes.collect {
      case c: Change.Text => c.path
      case c: Change.Put => c.path
    }
    for {
      a <- presentPaths
      b <- presentPaths
      if a != b && TrackedPath.isSegmentPrefixOf(a, b)
    } throw SnapError(s"tree paths conflict: $a and $b")
  }

  private val TextFields = Set("type", "path", "edit")
  private val PutFields = Set("type", "path", "content")
  private val DeleteFields = Set("type", "path")

  private def decodeChange(json: Json): Change = json match {
    case obj: Json.Obj =>
      val fields = obj.fields.toMap
      val changeType = requireString(field(fields, "type", "change"), "change type")
      val path = requireString(field(fields, "path", "change"), "change path")
      if (!TrackedPath.isValid(path)) throw SnapError(s"path is invalid: $path")

      changeType match {
        case "text" =>
          requireOnlyFields(obj, TextFields, "change")
          val editJson = requireArray(field(fields, "edit", "change"), "edit")
          val edit = editJson.map(decodeEditOp)
          validateEditScript(edit)
          Change.Text(path, edit)
        case "put" =>
          requireOnlyFields(obj, PutFields, "change")
          val content = requireString(field(fields, "content", "change"), "content")
          Change.Put(path, decodeBase64(content))
        case "delete" =>
          requireOnlyFields(obj, DeleteFields, "change")
          Change.Delete(path)
        case other =>
          throw SnapError(s"change has unknown type: $other")
      }
    case _ => throw SnapError("change must be an object")
  }

  /**
   * SPEC.md §4.3: "standard padded RFC 4648 base64." `java.util.Base64`'s decoder is
   * lenient about an unpadded final group (e.g. it accepts "abc", 3 chars, decoding it
   * as if padded), so canonical (padded) form needs an explicit length check on top.
   */
  private def decodeBase64(text: String): Vector[Byte] = {
    if (text.length % 4 != 0) throw SnapError("content is not canonical base64")
    try Base64.getDecoder.decode(text).toVector
    catch { case _: IllegalArgumentException => throw SnapError("content is not canonical base64") }
  }

  private def decodeEditOp(json: Json): EditOp = json match {
    case obj: Json.Obj =>
      if (obj.fields.length != 1) throw SnapError("edit operation must have one operation")
      obj.fields.head match {
        case ("retain", value) => EditOp.Retain(requirePositiveSafeInteger(value, "retain"))
        case ("delete", value) => EditOp.Delete(requirePositiveSafeInteger(value, "delete"))
        case ("insert", value) =>
          val items = requireArray(value, "insert")
          if (items.isEmpty) throw SnapError("edit insert is empty")
          EditOp.Insert(items.map {
            case Json.Str(s) if s.nonEmpty => s
            case Json.Str(_) => throw SnapError("insert token is empty")
            case _ => throw SnapError("insert token must be a string")
          })
        case (other, _) => throw SnapError(s"edit operation has unknown field: $other")
      }
    case _ => throw SnapError("edit operation must be an object")
  }

  /**
   * Structural rules only (SPEC.md §4.4), independent of any materialized base: no two
   * adjacent operations of the same kind, and — for a non-final token within one
   * `insert`'s own list — it must end in LF (we already know it isn't the file's final
   * token, since another token follows it in the same op), and no token may contain an
   * LF before its own final byte. Whether the *whole script* correctly consumes the
   * base's exact token count, and whether an empty script is valid here (only true when
   * creating an empty file), both depend on the materialized base and are checked later
   * by the replay engine, not here.
   */
  private def validateEditScript(ops: Vector[EditOp]): Unit = {
    ops.sliding(2).foreach {
      case Vector(a, b) =>
        val kind = (a, b) match {
          case (_: EditOp.Retain, _: EditOp.Retain) => Some("retain")
          case (_: EditOp.Delete, _: EditOp.Delete) => Some("delete")
          case (_: EditOp.Insert, _: EditOp.Insert) => Some("insert")
          case _ => None
        }
        kind.foreach(k => throw SnapError(s"adjacent $k operations"))
      case _ => ()
    }
    ops.foreach {
      case EditOp.Insert(tokens) =>
        val lastIndex = tokens.length - 1
        tokens.zipWithIndex.foreach { case (token, idx) =>
          val lfIndex = token.indexOf('\n')
          if (lfIndex >= 0 && lfIndex != token.length - 1) {
            throw SnapError("insert token contains a newline before its final byte")
          }
          if (idx != lastIndex && !token.endsWith("\n")) {
            throw SnapError("insert token must end with a newline unless it is the last token")
          }
        }
      case _ => ()
    }
  }

  /**
   * Decodes SPEC.md §3.2's JSON array form of a version: `[[id, revision], ...]`,
   * required here to already be in canonical (sorted, no duplicate) order — unlike
   * `Version.fromPairs`, which a caller may feed unordered pairs assembled in memory.
   */
  private def decodeVersion(json: Json, context: String): Version = {
    val items = requireArray(json, context)
    val pairs = items.map {
      case Json.Arr(Vector(idJson, revJson)) =>
        val id = requireString(idJson, s"$context entry id")
        if (!ContributorId.isValid(id)) throw SnapError(s"$context has invalid contributor id: $id")
        id -> requirePositiveSafeInteger(revJson, s"$context revision")
      case _ => throw SnapError(s"$context entry must be a [id, revision] pair")
    }
    val ids = pairs.map(_._1)
    if (ids.distinct.length != ids.length) {
      throw SnapError(s"$context has a duplicate contributor")
    }
    if (ids != ids.sorted) throw SnapError(s"$context is not in canonical (sorted) order")
    Version.fromPairs(pairs)
  }

  private def requirePositiveSafeInteger(json: Json, context: String): Long = json match {
    case n: Json.Num if n.isIntegral =>
      n.toBigIntOption match {
        case Some(v) if v >= 1 && v <= Version.MaxRevision => v.toLong
        case _ => throw SnapError(s"$context must be a positive safe integer")
      }
    case _ => throw SnapError(s"$context must be a positive safe integer")
  }

  private def requireString(json: Json, context: String): String = json match {
    case Json.Str(s) => s
    case _ => throw SnapError(s"$context must be a string")
  }

  private def requireArray(json: Json, context: String): Vector[Json] = json match {
    case Json.Arr(items) => items
    case _ => throw SnapError(s"$context must be an array")
  }

  private def requireOnlyFields(obj: Json.Obj, allowed: Set[String], context: String): Unit =
    obj.keys.foreach(k => if (!allowed(k)) throw SnapError(s"$context has unknown field: $k"))

  private def field(fields: Map[String, Json], name: String, context: String): Json =
    fields.getOrElse(name, throw SnapError(s"$context missing $name"))

  // ---- encoding -------------------------------------------------------------------------

  private def encodeVersion(v: Version): Json =
    Json.Arr(v.sortedComponents.map { case (id, rev) =>
      Json.Arr(Vector(Json.Str(id), Json.Num(rev.toString, isIntegral = true)))
    })

  private def encodePatch(p: Patch): Json =
    Json.Obj(
      Vector(
        "author" -> Json.Str(p.author),
        "revision" -> Json.Num(p.revision.toString, isIntegral = true),
        "base" -> encodeVersion(p.base),
        "message" -> Json.Str(p.message),
        "changes" -> Json.Arr(p.changes.map(encodeChange))
      )
    )

  private def encodeChange(c: Change): Json = c match {
    case Change.Text(path, edit) =>
      Json.Obj(
        Vector(
          "type" -> Json.Str("text"),
          "path" -> Json.Str(path),
          "edit" -> Json.Arr(edit.map(encodeEditOp))
        )
      )
    case Change.Put(path, content) =>
      val encoded = Base64.getEncoder.encodeToString(content.toArray)
      Json.Obj(
        Vector("type" -> Json.Str("put"), "path" -> Json.Str(path), "content" -> Json.Str(encoded))
      )
    case Change.Delete(path) =>
      Json.Obj(Vector("type" -> Json.Str("delete"), "path" -> Json.Str(path)))
  }

  private def encodeEditOp(op: EditOp): Json = op match {
    case EditOp.Retain(n) => Json.Obj(Vector("retain" -> Json.Num(n.toString, isIntegral = true)))
    case EditOp.Delete(n) => Json.Obj(Vector("delete" -> Json.Num(n.toString, isIntegral = true)))
    case EditOp.Insert(tokens) => Json.Obj(Vector("insert" -> Json.Arr(tokens.map(Json.Str))))
  }
}
