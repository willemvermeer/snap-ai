package snap.diff

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}

/**
 * SPEC.md §4.4's text/binary classification and tokenization: "A file is text when its
 * bytes are valid UTF-8 and contain no NUL. Split it immediately after every LF byte,
 * retaining LF in the token." The empty file has no tokens.
 */
object TextTokens {

  /**
   * Strict UTF-8 validity (rejects malformed sequences, overlong encodings, and
   * unpaired surrogates — Java's decoder is RFC 3629-compliant in report mode) plus the
   * no-NUL rule. Bytes that pass this are safe to decode with `toText`.
   */
  def isText(bytes: Array[Byte]): Boolean =
    !bytes.contains(0.toByte) && {
      val decoder = StandardCharsets.UTF_8.newDecoder
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      try {
        decoder.decode(ByteBuffer.wrap(bytes))
        true
      } catch { case _: CharacterCodingException => false }
    }

  /**
   * Only valid to call once `isText` has confirmed strict UTF-8; this uses the lenient
   * default decoder purely because it's cheaper, not to re-validate.
   */
  def toText(bytes: Array[Byte]): String = new String(bytes, StandardCharsets.UTF_8)

  /**
   * Splits immediately after every LF (`\n`) byte, keeping the LF as part of the
   * preceding token; a final token with no trailing LF is kept as-is. LF is a single
   * ASCII byte with no multi-byte UTF-8 encoding, so splitting the decoded `String` on
   * `'\n'` is equivalent to splitting the original bytes on the LF byte.
   */
  def tokenize(text: String): Vector[String] =
    if (text.isEmpty) {
      Vector.empty
    } else {
      val tokens = Vector.newBuilder[String]
      var start = 0
      var i = 0
      while (i < text.length) {
        if (text.charAt(i) == '\n') {
          tokens += text.substring(start, i + 1)
          start = i + 1
        }
        i += 1
      }
      if (start < text.length) tokens += text.substring(start)
      tokens.result()
    }
}
