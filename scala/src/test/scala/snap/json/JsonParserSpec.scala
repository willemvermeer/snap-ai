package snap.json

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.SnapError

class JsonParserSpec extends AnyFunSuite with Matchers {

  test("parses primitives") {
    JsonParser.parse("true") shouldBe Json.Bool(true)
    JsonParser.parse("false") shouldBe Json.Bool(false)
    JsonParser.parse("null") shouldBe Json.Null
    JsonParser.parse("\"hi\"") shouldBe Json.Str("hi")
    JsonParser.parse("0") shouldBe Json.Num("0", isIntegral = true)
    JsonParser.parse("-12") shouldBe Json.Num("-12", isIntegral = true)
    JsonParser.parse("2323") shouldBe Json.Num("2323", isIntegral = true)
  }

  test("parses decimals and exponents as non-integral numbers") {
    JsonParser.parse("1.5").asInstanceOf[Json.Num].isIntegral shouldBe false
    JsonParser.parse("1e10").asInstanceOf[Json.Num].isIntegral shouldBe false
    JsonParser.parse("1E-3").asInstanceOf[Json.Num].isIntegral shouldBe false
    JsonParser.parse("42").asInstanceOf[Json.Num].isIntegral shouldBe true
  }

  test("rejects leading-zero numbers") {
    a[SnapError] should be thrownBy JsonParser.parse("012")
  }

  test("parses nested arrays and objects preserving key order") {
    val json = JsonParser.parse("""{"b": 1, "a": [1, 2, {"z": true, "y": null}]}""")
    json shouldBe Json.Obj(
      Vector(
        "b" -> Json.Num("1", isIntegral = true),
        "a" -> Json.Arr(
          Vector(
            Json.Num("1", isIntegral = true),
            Json.Num("2", isIntegral = true),
            Json.Obj(Vector("z" -> Json.Bool(true), "y" -> Json.Null))
          )
        )
      )
    )
  }

  test("parses string escapes including unicode") {
    JsonParser.parse(""""a\nb\t\"\\\/c"""") shouldBe Json.Str("a\nb\t\"\\/c")
    JsonParser.parse(""""A"""") shouldBe Json.Str("A")
  }

  test("rejects duplicate object keys") {
    val ex = the[SnapError] thrownBy JsonParser.parse("""{"format":1,"format":1}""")
    ex.message should include("duplicate JSON key")
    ex.message should include("\"format\"")
  }

  test("rejects malformed JSON with an 'invalid JSON' message") {
    val ex = the[SnapError] thrownBy JsonParser.parse("not json")
    ex.message should include("invalid JSON")
  }

  test("rejects empty input") {
    a[SnapError] should be thrownBy JsonParser.parse("")
  }

  test("rejects trailing content") {
    a[SnapError] should be thrownBy JsonParser.parse("{} garbage")
  }

  test("rejects unterminated strings and control characters in strings") {
    a[SnapError] should be thrownBy JsonParser.parse("\"unterminated")
    a[SnapError] should be thrownBy JsonParser.parse(
      "\"has\ttab\""
    ) // raw control char, not the \t escape
  }

  test("accepts whitespace around tokens") {
    JsonParser.parse("  { \"a\" : 1 }  \n") shouldBe Json.Obj(
      Vector("a" -> Json.Num("1", isIntegral = true))
    )
  }

  test("accepts empty arrays and objects") {
    JsonParser.parse("[]") shouldBe Json.Arr(Vector.empty)
    JsonParser.parse("{}") shouldBe Json.Obj(Vector.empty)
  }
}
