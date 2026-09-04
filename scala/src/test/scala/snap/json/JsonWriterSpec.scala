package snap.json

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class JsonWriterSpec extends AnyFunSuite with Matchers {

  test("two-space indents nested structures and ends with a trailing LF") {
    val json = Json.Obj(
      Vector(
        "a" -> Json.Arr(Vector(Json.Num("1", isIntegral = true), Json.Num("2", isIntegral = true)))
      )
    )
    JsonWriter.write(json) shouldBe
      "{\n  \"a\": [\n    1,\n    2\n  ]\n}\n"
  }

  test("renders empty objects and arrays inline") {
    JsonWriter.write(Json.Obj(Vector.empty)) shouldBe "{}\n"
    JsonWriter.write(Json.Arr(Vector.empty)) shouldBe "[]\n"
  }

  test("escapes strings") {
    JsonWriter.write(Json.Str("a\"b\\c\nd")) shouldBe "\"a\\\"b\\\\c\\nd\"\n"
  }

  test("escapes carriage return, tab, and other control characters") {
    JsonWriter.write(Json.Str("a\rb")) shouldBe "\"a\\rb\"\n"
    JsonWriter.write(Json.Str("a\tb")) shouldBe "\"a\\tb\"\n"
    // The expected value needs the literal six-character escape sequence to
    // survive as text rather than being consumed by unicode-escape
    // preprocessing, so it is built by concatenation instead of written directly
    // (see the longer explanation in RepositoryCodecSpec).
    val literalEscape = "\\" + "u0001"
    JsonWriter.write(Json.Str("ab")) shouldBe ("\"" + "a" + literalEscape + "b" + "\"" + "\n")
  }

  test("writes null and boolean values") {
    JsonWriter.write(Json.Null) shouldBe "null\n"
    JsonWriter.write(Json.Bool(true)) shouldBe "true\n"
    JsonWriter.write(Json.Bool(false)) shouldBe "false\n"
  }

  test("round-trips through the parser") {
    val original = """{"format":1,"frontier":[["alice@example.com",1]],"patches":[]}"""
    val parsed = JsonParser.parse(original)
    val reparsed = JsonParser.parse(JsonWriter.write(parsed))
    reparsed shouldBe parsed
  }
}
