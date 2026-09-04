package snap.id

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.SnapError

class ContributorIdSpec extends AnyFunSuite with Matchers {

  test("accepts ordinary email-shaped ids") {
    ContributorId.isValid("alice@example.com") shouldBe true
    ContributorId.isValid("jdegoes@example.com") shouldBe true
    ContributorId.isValid("ab@x") shouldBe true
  }

  test("rejects zero or multiple at-signs") {
    ContributorId.isValid("no-at-sign") shouldBe false
    ContributorId.isValid("a@b@c") shouldBe false
  }

  test("rejects empty text on either side of the at-sign") {
    ContributorId.isValid("@example.com") shouldBe false
    ContributorId.isValid("alice@") shouldBe false
  }

  test("rejects whitespace and forbidden punctuation") {
    ContributorId.isValid("space @x") shouldBe false
    ContributorId.isValid("a,b@x") shouldBe false
    ContributorId.isValid("a(b)@x") shouldBe false
    ContributorId.isValid("a->b@x") shouldBe false
  }

  test("rejects control characters") {
    val controlBefore = "a" + '\u0001' + "@x"
    val controlAfter = "a@x" + '\u007f'
    ContributorId.isValid(controlBefore) shouldBe false
    ContributorId.isValid(controlAfter) shouldBe false
  }

  test("rejects non-ASCII characters") {
    ContributorId.isValid("café@example.com") shouldBe false
  }

  test("rejects ids over 254 bytes") {
    val longLocal = "a" * 254
    ContributorId.isValid(s"$longLocal@x") shouldBe false
  }

  test("accepts ids at exactly 254 bytes") {
    val local = "a" * (254 - "@x".length)
    val id = s"$local@x"
    id.length shouldBe 254
    ContributorId.isValid(id) shouldBe true
  }

  test("require throws SnapError with the exact required message shape") {
    val ex = the[SnapError] thrownBy ContributorId.require("two@@x")
    ex.message should include("invalid contributor id:")
  }

  test("require passes through a valid id") {
    ContributorId.require("alice@example.com") shouldBe "alice@example.com"
  }
}
