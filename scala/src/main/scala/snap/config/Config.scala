package snap.config

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import snap.SnapError
import snap.id.ContributorId
import snap.json.{Json, JsonParser, JsonWriter}

final case class ContributorConfig(id: String)

/**
 * Reads and writes `.snap/config.json` / `$HOME/.snapconfig.json`, per SPEC.md §8 and
 * the `snap config` command in §7.2.
 *
 * Schema-shape validation (unknown fields, wrong types) happens here on every read.
 * Contributor-ID grammar validation is deferred to the point of use (`resolveContributorId`,
 * or `write`), matching §8: an ID that is syntactically present but invalid is still a hard
 * error, distinct from a config file that is simply absent.
 */
object Config {
  private val Context = "config"

  private def decode(json: Json): ContributorConfig = json match {
    case obj: Json.Obj =>
      obj.keys.foreach(k =>
        if (k != "contributor") throw SnapError(s"$Context has unknown field: $k")
      )
      val contributorJson =
        obj.get("contributor").getOrElse(throw SnapError(s"$Context missing contributor"))
      contributorJson match {
        case cobj: Json.Obj =>
          cobj.keys.foreach(k => if (k != "id") throw SnapError(s"$Context has unknown field: $k"))
          cobj.get("id").getOrElse(throw SnapError(s"$Context missing contributor.id")) match {
            case Json.Str(id) => ContributorConfig(id)
            case _ => throw SnapError(s"$Context contributor.id must be a string")
          }
        case _ => throw SnapError(s"$Context contributor must be an object")
      }
    case _ => throw SnapError(s"$Context must be an object")
  }

  /**
   * `None` when the file is absent ("a missing file means no value", §8). A present but
   * malformed file is always a hard error.
   */
  def read(path: Path): Option[ContributorConfig] =
    if (!Files.isRegularFile(path)) {
      None
    } else {
      val text = new String(Files.readAllBytes(path), UTF_8)
      Some(decode(JsonParser.parse(text)))
    }

  def write(path: Path, id: String): Unit = {
    val validId = ContributorId.require(id)
    val json = Json.Obj(Vector("contributor" -> Json.Obj(Vector("id" -> Json.Str(validId)))))
    Files.write(path, JsonWriter.write(json).getBytes(UTF_8))
  }

  def localPath(snapDir: Path): Path = snapDir.resolve("config.json")
  def globalPath(home: Path): Path = home.resolve(".snapconfig.json")

  /**
   * §8's local-over-global precedence: if the local file provides an ID, global
   * configuration is never read. Only `commit` and `revert` call this, and both require
   * an identity: a missing value on both sides is the exact error text from §8.
   */
  def resolveContributorId(localSnapDir: Option[Path], home: Option[Path]): String = {
    val local = localSnapDir.flatMap(dir => read(localPath(dir)))
    local match {
      case Some(cfg) => ContributorId.require(cfg.id)
      case None =>
        val global = home.flatMap(h => read(globalPath(h)))
        global match {
          case Some(cfg) => ContributorId.require(cfg.id)
          case None =>
            throw SnapError("contributor.id is required; configure it locally or globally")
        }
    }
  }
}
