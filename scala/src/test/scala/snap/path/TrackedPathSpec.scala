package snap.path

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class TrackedPathSpec extends AnyFunSuite with Matchers {

  test("accepts ordinary relative paths") {
    TrackedPath.isValid("hello.txt") shouldBe true
    TrackedPath.isValid("src/main.ts") shouldBe true
    TrackedPath.isValid("a/b/c") shouldBe true
  }

  test("rejects empty paths") {
    TrackedPath.isValid("") shouldBe false
  }

  test("rejects backslash and control characters") {
    TrackedPath.isValid("a\\b") shouldBe false
    TrackedPath.isValid("a\u0001b") shouldBe false
    TrackedPath.isValid("a\u007fb") shouldBe false
  }

  test("rejects empty, '.', or '..' segments") {
    TrackedPath.isValid("a//b") shouldBe false
    TrackedPath.isValid("/a") shouldBe false
    TrackedPath.isValid("a/") shouldBe false
    TrackedPath.isValid("./a") shouldBe false
    TrackedPath.isValid("a/./b") shouldBe false
    TrackedPath.isValid("../a") shouldBe false
    TrackedPath.isValid("a/../b") shouldBe false
  }

  test("rejects a first segment of .snap but allows .snap deeper in the path") {
    TrackedPath.isValid(".snap/secret") shouldBe false
    TrackedPath.isValid(".snap") shouldBe false
    TrackedPath.isValid("a/.snap") shouldBe true
  }

  test("ordering sorts ASCII paths lexicographically") {
    TrackedPath.ordering.lt("a", "b") shouldBe true
    TrackedPath.ordering.lt("a", "ab") shouldBe true
    TrackedPath.ordering.equiv("same", "same") shouldBe true
  }

  test("ordering follows unsigned UTF-8 byte order, unlike default UTF-16 String order") {
    // U+E000 (BMP private-use) vs U+1F600 (a supplementary-plane emoji, encoded in UTF-16
    // as a surrogate pair whose leading unit 0xD83D is numerically less than 0xE000).
    // Default String ordering compares UTF-16 code units and gets this backwards; unsigned
    // UTF-8 byte order (SPEC.md §2) puts the higher code point after, correctly.
    val bmp = "\uE000"
    val supplementary = "\ud83d\ude00"
    bmp < supplementary shouldBe false // default String ordering disagrees with the spec
    TrackedPath.ordering.lt(bmp, supplementary) shouldBe true
  }

  test("ordering treats a shorter path as less than an extension of it") {
    TrackedPath.ordering.lt("a", "a/b") shouldBe true
  }
}
