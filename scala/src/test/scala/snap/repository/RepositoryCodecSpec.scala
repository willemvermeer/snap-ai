package snap.repository

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import snap.SnapError
import snap.json.JsonParser
import snap.version.Version

/**
 * Mirrors the schema-level scenarios from tests/15-repository-validation.yaml,
 * tests/23-strict-validation-matrix.yaml, and tests/27-history-canonicality.yaml —
 * everything within SPEC.md §4.5 steps 1-4's scope. The replay-dependent cases in those
 * same files (validating a change against its materialized base — step 5) aren't
 * covered here; they need plan unit 7's replay engine.
 */
class RepositoryCodecSpec extends AnyFunSuite with Matchers {

  private def decode(text: String): Repository = RepositoryCodec.decode(JsonParser.parse(text))
  private def failsWith(text: String)(assertion: String => Unit): Unit = {
    val ex = the[SnapError] thrownBy decode(text)
    assertion(ex.message)
  }

  test("decodes a minimal valid repository") {
    val repo = decode("""{"format":1,"frontier":[],"patches":[]}""")
    repo shouldBe Repository(Version.Empty, Vector.empty)
  }

  test("decodes a repository with one text-creating patch") {
    val repo = decode(
      """{"format":1,"frontier":[["alice@example.com",1]],"patches":[
        |{"author":"alice@example.com","revision":1,"base":[],"message":"add greeting",
        | "changes":[{"type":"text","path":"hello.txt","edit":[{"insert":["hello\n"]}]}]}
        |]}""".stripMargin
    )
    repo.frontier shouldBe Version.fromPairs(Seq("alice@example.com" -> 1L))
    repo.patches shouldBe Vector(
      Patch(
        "alice@example.com",
        1,
        Version.Empty,
        "add greeting",
        Vector(Change.Text("hello.txt", Vector(EditOp.Insert(Vector("hello\n")))))
      )
    )
  }

  test("rejects an unknown top-level field") {
    failsWith("""{"format":1,"frontier":[],"patches":[],"unknown":true}""")(
      _ shouldBe "repository has unknown field: unknown"
    )
  }

  test("rejects a non-1 format") {
    failsWith("""{"format":2,"frontier":[],"patches":[]}""")(_ should include("format"))
  }

  test("rejects a repository that is not an object") {
    a[SnapError] should be thrownBy decode("[]")
  }

  test("rejects a frontier referencing a missing patch") {
    failsWith(
      """{"format":1,"frontier":[["a@x",2]],"patches":[
        |{"author":"a@x","revision":2,"base":[["a@x",1]],"message":"gap",
        | "changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    )(_ should include("missing a@x"))
  }

  test("rejects an invalid tracked path") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"bad path",
        | "changes":[{"type":"put","path":".snap/secret","content":"YQ=="}]}
        |]}""".stripMargin
    )(_ should include("path is invalid"))
  }

  test("rejects non-canonical base64 content") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"bad bytes",
        | "changes":[{"type":"put","path":"f","content":"abc"}]}
        |]}""".stripMargin
    )(_ should include("canonical base64"))
  }

  test("rejects tree paths within one patch that collide by prefix") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"prefix",
        | "changes":[
        |   {"type":"put","path":"a","content":"YQ=="},
        |   {"type":"put","path":"a/b","content":"Yg=="}
        | ]}
        |]}""".stripMargin
    )(_ should include("tree paths conflict"))
  }

  test("does not flag a prefix conflict when one side is a delete") {
    decode(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"delete then create",
        | "changes":[
        |   {"type":"delete","path":"a"},
        |   {"type":"put","path":"a/b","content":"Yg=="}
        | ]}
        |]}""".stripMargin
    )
  }

  test("rejects a non-integer revision") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1.5,"base":[],"message":"fraction",
        | "changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    )(_ should endWith("positive safe integer"))
  }

  test("rejects an unreachable patch not connected to frontier") {
    failsWith(
      """{"format":1,"frontier":[],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"unreachable",
        | "changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    )(_ should include("unreachable patch"))
  }

  test("rejects an empty message") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"",
        | "changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    )(_ should endWith("message is empty"))
  }

  test("rejects a message with a disallowed control character") {
    // Built via concatenation: writing that six-character escape sequence directly
    // in this source file, in any form, gets silently consumed into an actual raw
    // control character before this string literal is even parsed (unicode-escape
    // preprocessing runs on raw source text, comments included). This test wants the
    // JSON parser, not that earlier preprocessing step, to be the one decoding it.
    val jsonEscapedBel = "\\" + "u0007"
    failsWith(
      s"""{"format":1,"frontier":[["a@x",1]],"patches":[
         |{"author":"a@x","revision":1,"base":[],"message":"bad${jsonEscapedBel}msg",
         | "changes":[{"type":"text","path":"f","edit":[]}]}
         |]}""".stripMargin
    )(_ should include("control character"))
  }

  test("accepts a message containing tab and newline") {
    decode(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"line one\tcol\nline two",
        | "changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    )
  }

  test("rejects empty changes") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"none","changes":[]}
        |]}""".stripMargin
    )(_ should endWith("changes is empty"))
  }

  test("rejects an unknown change field") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"extra field",
        | "changes":[{"type":"put","path":"f","content":"YQ==","extra":1}]}
        |]}""".stripMargin
    )(_ should endWith("unknown field: extra"))
  }

  test("rejects an edit operation with more than one key") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"bad op",
        | "changes":[{"type":"text","path":"f","edit":[{"retain":1,"delete":1}]}]}
        |]}""".stripMargin
    )(_ should endWith("must have one operation"))
  }

  test("rejects a non-positive edit-op count") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"bad count",
        | "changes":[{"type":"text","path":"f","edit":[{"retain":0}]}]}
        |]}""".stripMargin
    )(_ should endWith("positive safe integer"))
  }

  test("rejects an empty insert") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"empty insert",
        | "changes":[{"type":"text","path":"f","edit":[{"insert":[]}]}]}
        |]}""".stripMargin
    )(_ should endWith("insert is empty"))
  }

  test("rejects adjacent insert operations") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"adjacent",
        | "changes":[{"type":"text","path":"f",
        |   "edit":[{"insert":["a\n"]},{"insert":["b\n"]}]}]}
        |]}""".stripMargin
    )(_ should include("adjacent insert"))
  }

  test("rejects adjacent retain operations") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"adjacent retain",
        | "changes":[{"type":"text","path":"f","edit":[{"retain":1},{"retain":1}]}]}
        |]}""".stripMargin
    )(_ should include("adjacent retain"))
  }

  test("rejects an insert token that isn't last and lacks a trailing newline") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"bad token",
        | "changes":[{"type":"text","path":"f","edit":[{"insert":["a","b"]}]}]}
        |]}""".stripMargin
    )(_ should include("newline"))
  }

  test("rejects an insert token with an internal newline") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"embedded lf",
        | "changes":[{"type":"text","path":"f","edit":[{"insert":["a\nb\n"]}]}]}
        |]}""".stripMargin
    )(_ should include("newline"))
  }

  test("rejects a revision that doesn't equal base[author] + 1") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[["a@x",1]],"message":"wrong dot",
        | "changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    )(_ should include("does not follow its base"))
  }

  test("rejects patch changes not sorted by path") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"order",
        | "changes":[{"type":"text","path":"z","edit":[]},{"type":"text","path":"a","edit":[]}]}
        |]}""".stripMargin
    )(_ should include("sorted by path"))
  }

  test("rejects patch changes with a duplicate path") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"dup",
        | "changes":[{"type":"text","path":"a","edit":[]},{"type":"put","path":"a","content":"YQ=="}]}
        |]}""".stripMargin
    )(_ should include("duplicate path"))
  }

  test("rejects patches not sorted by author then revision") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1],["b@x",1]],"patches":[
        |{"author":"b@x","revision":1,"base":[],"message":"b","changes":[{"type":"text","path":"b","edit":[]}]},
        |{"author":"a@x","revision":1,"base":[],"message":"a","changes":[{"type":"text","path":"a","edit":[]}]}
        |]}""".stripMargin
    )(_ should include("sorted"))
  }

  test("rejects a duplicate patch dot") {
    val json = JsonParser.parse(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"one","changes":[{"type":"text","path":"a","edit":[]}]},
        |{"author":"a@x","revision":1,"base":[],"message":"two","changes":[{"type":"text","path":"b","edit":[]}]}
        |]}""".stripMargin
    )
    val ex = the[SnapError] thrownBy RepositoryCodec.decode(json)
    ex.message should include("duplicate patch dot")
  }

  test("rejects a cyclic patch history") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1],["b@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[["b@x",1]],"message":"cycle a","changes":[{"type":"text","path":"a","edit":[]}]},
        |{"author":"b@x","revision":1,"base":[["a@x",1]],"message":"cycle b","changes":[{"type":"text","path":"b","edit":[]}]}
        |]}""".stripMargin
    )(_ should include("cyclic or incomplete patch history"))
  }

  test("rejects a frontier version array that is not canonically sorted") {
    failsWith(
      """{"format":1,"frontier":[["b@x",1],["a@x",1]],"patches":[]}"""
    )(_ should include("canonical"))
  }

  test("rejects a base version array with a duplicate contributor") {
    failsWith(
      """{"format":1,"frontier":[["a@x",2]],"patches":[
        |{"author":"a@x","revision":2,"base":[["a@x",1],["a@x",1]],"message":"dup base",
        | "changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    )(_ should include("duplicate"))
  }

  test("rejects a patch with an invalid author id") {
    failsWith(
      """{"format":1,"frontier":[],"patches":[
        |{"author":"not-an-id","revision":1,"base":[],"message":"m",
        | "changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    )(_ should include("invalid contributor id"))
  }

  test("rejects a patch entry that is not an object") {
    failsWith("""{"format":1,"frontier":[],"patches":[1]}""")(_ shouldBe "patch must be an object")
  }

  test("rejects a change with an unknown type") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"m",
        | "changes":[{"type":"rename","path":"f"}]}
        |]}""".stripMargin
    )(_ should include("unknown type"))
  }

  test("rejects a change entry that is not an object") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"m","changes":[1]}
        |]}""".stripMargin
    )(_ shouldBe "change must be an object")
  }

  test("rejects base64 content with valid length but invalid characters") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"m",
        | "changes":[{"type":"put","path":"f","content":"!!!!"}]}
        |]}""".stripMargin
    )(_ should include("canonical base64"))
  }

  test("decodes a standalone delete edit operation") {
    val repo = decode(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"m",
        | "changes":[{"type":"text","path":"f","edit":[{"delete":3}]}]}
        |]}""".stripMargin
    )
    repo.patches.head.changes shouldBe Vector(Change.Text("f", Vector(EditOp.Delete(3))))
  }

  test("rejects an empty-string insert token") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"m",
        | "changes":[{"type":"text","path":"f","edit":[{"insert":[""]}]}]}
        |]}""".stripMargin
    )(_ should include("insert token is empty"))
  }

  test("rejects a non-string insert token") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"m",
        | "changes":[{"type":"text","path":"f","edit":[{"insert":[1]}]}]}
        |]}""".stripMargin
    )(_ should include("insert token must be a string"))
  }

  test("rejects an edit operation with a single unrecognized key") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"m",
        | "changes":[{"type":"text","path":"f","edit":[{"skip":1}]}]}
        |]}""".stripMargin
    )(_ should include("unknown field: skip"))
  }

  test("rejects an edit operation entry that is not an object") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"m",
        | "changes":[{"type":"text","path":"f","edit":[1]}]}
        |]}""".stripMargin
    )(_ shouldBe "edit operation must be an object")
  }

  test("rejects adjacent delete operations") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"m",
        | "changes":[{"type":"text","path":"f","edit":[{"delete":1},{"delete":1}]}]}
        |]}""".stripMargin
    )(_ should include("adjacent delete"))
  }

  test("rejects a version array entry with an invalid contributor id") {
    failsWith(
      """{"format":1,"frontier":[["not-an-id",1]],"patches":[]}"""
    )(_ should include("invalid contributor id"))
  }

  test("rejects a version array entry that is not an [id, revision] pair") {
    failsWith(
      """{"format":1,"frontier":[["a@x"]],"patches":[]}"""
    )(_ should include("[id, revision] pair"))
  }

  test("rejects a patch field with the wrong JSON type") {
    failsWith(
      """{"format":1,"frontier":[],"patches":[
        |{"author":1,"revision":1,"base":[],"message":"m",
        | "changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    )(_ should include("must be a string"))
  }

  test("rejects a changes field that is not an array") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"m","changes":"nope"}
        |]}""".stripMargin
    )(_ should include("must be an array"))
  }

  test("rejects a patch missing a required field") {
    failsWith(
      """{"format":1,"frontier":[["a@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],
        | "changes":[{"type":"text","path":"f","edit":[]}]}
        |]}""".stripMargin
    )(_ should include("missing message"))
  }

  test("encode is the inverse of decode") {
    val original = decode(
      """{"format":1,"frontier":[["a@x",2],["b@x",1]],"patches":[
        |{"author":"a@x","revision":1,"base":[],"message":"base",
        | "changes":[{"type":"put","path":"bin","content":"AAEC"}]},
        |{"author":"a@x","revision":2,"base":[["a@x",1]],"message":"more",
        | "changes":[{"type":"text","path":"f",
        |   "edit":[{"retain":1},{"delete":1},{"insert":["one\n","two\n"]}]}]},
        |{"author":"b@x","revision":1,"base":[],"message":"delete later",
        | "changes":[{"type":"delete","path":"gone"}]}
        |]}""".stripMargin
    )
    val roundTripped = RepositoryCodec.decode(RepositoryCodec.encode(original))
    roundTripped shouldBe original
  }
}
