package snap.json

/** A parsed JSON value. Objects preserve source key order; the parser rejects duplicate keys. */
sealed trait Json

object Json {
  case object Null extends Json
  final case class Bool(value: Boolean) extends Json

  /**
   * `isIntegral` reflects the source literal's lexical shape (no '.' or exponent), not
   * merely whether its value happens to be whole — "5e2" is not integral even though it
   * equals 500. The parser sets this from json4s's own JInt/JLong vs JDouble/JDecimal
   * split, which already tracks that same distinction during scanning.
   */
  final case class Num(raw: String, isIntegral: Boolean) extends Json {
    def toBigIntOption: Option[BigInt] =
      if (isIntegral) scala.util.Try(BigInt(raw)).toOption else None
  }
  final case class Str(value: String) extends Json
  final case class Arr(items: Vector[Json]) extends Json
  final case class Obj(fields: Vector[(String, Json)]) extends Json {
    def get(key: String): Option[Json] = fields.collectFirst { case (k, v) if k == key => v }
    def keys: Vector[String] = fields.map(_._1)
  }
}
