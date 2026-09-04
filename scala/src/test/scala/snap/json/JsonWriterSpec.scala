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

  test("round-trips through the parser") {
    val original = """{"format":1,"frontier":[["alice@example.com",1]],"patches":[]}"""
    val parsed = JsonParser.parse(original)
    val reparsed = JsonParser.parse(JsonWriter.write(parsed))
    reparsed shouldBe parsed
  }
}
