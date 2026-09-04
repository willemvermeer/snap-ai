package snap.json

import com.fasterxml.jackson.core.JsonFactory
import org.json4s._
import org.json4s.jackson.JsonMethods
import snap.SnapError

/**
 * Parses JSON via json4s-jackson (Jackson does the character-level scanning: numbers,
 * string escapes, structure), then converts to Snap's own `Json` ADT. The conversion
 * step adds what SPEC.md §4.1/§4.5 require beyond plain JSON: rejecting duplicate object
 * keys (JSON itself leaves that unspecified, and json4s keeps every field it sees rather
 * than deduping), and preserving source key order end to end.
 *
 * Jackson's `ObjectMapper`-based `readValue`, which json4s-jackson uses internally, does
 * not by default reject trailing content after the root value, so that check is done
 * separately here with Jackson's low-level streaming parser.
 */
object JsonParser {
  private val jsonFactory = new JsonFactory()

  def parse(text: String): Json = {
    if (text.trim.isEmpty) throw SnapError("invalid JSON: empty input")
    val jvalue =
      try JsonMethods.parse(text, useBigDecimalForDouble = true)
      catch {
        case ex: Exception => throw SnapError(s"invalid JSON: ${ex.getMessage}")
      }
    // No separate JNothing check here: the `text.trim.isEmpty` guard above already
    // catches every blank input, and jackson-backed parsing of any non-blank text either
    // throws (caught above) or returns a real value — never JNothing.
    rejectTrailingContent(text)
    convert(jvalue)
  }

  private def rejectTrailingContent(text: String): Unit = {
    val parser = jsonFactory.createParser(text)
    try {
      parser.nextToken() // the root value's first token
      parser.skipChildren() // no-op unless the root is an object/array
      val hasMore =
        try parser.nextToken() != null
        catch {
          case _: Exception => true
        } // malformed trailing bytes still count as trailing content
      if (hasMore) throw SnapError("invalid JSON: unexpected trailing content")
    } finally parser.close()
  }

  private def convert(v: JValue): Json = v match {
    case JNull | JNothing => Json.Null
    case JBool(b) => Json.Bool(b)
    case JString(s) => Json.Str(s)
    case JInt(bigInt) => Json.Num(bigInt.toString, isIntegral = true)
    case JDecimal(bd) => Json.Num(bd.bigDecimal.toPlainString, isIntegral = false)
    case JArray(items) => Json.Arr(items.map(convert).toVector)
    // json4s-jackson's deserializer always produces JInt for integral JSON numbers
    // (never JLong) and, since `parse` always passes useBigDecimalForDouble = true,
    // JDecimal for every non-integral one (never JDouble); JSet has no JSON textual form
    // at all. These three branches exist only to keep this match exhaustive against
    // json4s's full JValue ADT — real parsed input never reaches them.
    // $COVERAGE-OFF$
    case JLong(l) => Json.Num(l.toString, isIntegral = true)
    case JDouble(d) => Json.Num(BigDecimal(d).bigDecimal.toPlainString, isIntegral = false)
    case JSet(items) => Json.Arr(items.map(convert).toVector)
    // $COVERAGE-ON$
    case JObject(fields) =>
      var seen = Set.empty[String]
      fields.foreach { case (key, _) =>
        if (seen(key)) throw SnapError(s"""duplicate JSON key "$key"""")
        seen += key
      }
      Json.Obj(fields.map { case (key, value) => key -> convert(value) }.toVector)
  }
}
