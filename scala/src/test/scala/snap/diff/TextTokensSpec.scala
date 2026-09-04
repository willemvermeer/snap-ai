package snap.diff

import java.nio.charset.StandardCharsets.UTF_8
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class TextTokensSpec extends AnyFunSuite with Matchers {

  test("tokenize splits immediately after every LF, retaining it") {
    TextTokens.tokenize("a\r\nb") shouldBe Vector("a\r\n", "b")
  }

  test("tokenize on the empty string produces no tokens") {
    TextTokens.tokenize("") shouldBe Vector.empty
  }

  test("tokenize keeps a trailing token with no LF as-is") {
    TextTokens.tokenize("one\ntwo") shouldBe Vector("one\n", "two")
  }

  test("tokenize on content ending in LF produces no trailing empty token") {
    TextTokens.tokenize("one\ntwo\n") shouldBe Vector("one\n", "two\n")
  }

  test("tokenize on a single LF produces one token") {
    TextTokens.tokenize("\n") shouldBe Vector("\n")
  }

  test("isText accepts plain ASCII and multi-byte UTF-8") {
    TextTokens.isText("hello\n".getBytes(UTF_8)) shouldBe true
    TextTokens.isText("héllo\n".getBytes(UTF_8)) shouldBe true
  }

  test("isText rejects a NUL byte") {
    TextTokens.isText(Array[Byte](104, 0, 105)) shouldBe false
  }

  test("isText rejects malformed UTF-8") {
    TextTokens.isText(Array[Byte](0xff.toByte, 0xfe.toByte)) shouldBe false
  }

  test("isText rejects an overlong UTF-8 encoding") {
    // A two-byte overlong encoding of U+0041 ('A'), which must be one byte — invalid
    // per RFC 3629 even though it decodes to a plausible-looking code point leniently.
    TextTokens.isText(Array[Byte](0xc1.toByte, 0x81.toByte)) shouldBe false
  }

  test("isText rejects a lone continuation byte and a truncated sequence") {
    TextTokens.isText(Array[Byte](0x80.toByte)) shouldBe false
    TextTokens.isText(Array[Byte](0xe2.toByte, 0x82.toByte)) shouldBe false // truncated €
  }

  test("isText accepts the empty byte array") {
    TextTokens.isText(Array.empty[Byte]) shouldBe true
  }

  test("toText decodes bytes already confirmed as text") {
    val bytes = "café\n".getBytes(UTF_8)
    TextTokens.isText(bytes) shouldBe true
    TextTokens.toText(bytes) shouldBe "café\n"
  }
}
