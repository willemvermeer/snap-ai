package snap.diff

import snap.repository.EditOp

/**
 * SPEC.md §5's canonical token diff: the exact `D(i,j)` minimum-edit-distance recurrence
 * with its deletion-on-tie rule, walked and coalesced into an [[EditOp]] script. Every
 * patch-creating command, `diff`, and OT (plan unit 7) share this one function so the
 * script for any given `(old, new)` pair is identical everywhere it's produced.
 *
 * This computes the full `(n+1) x (m+1)` DP table directly rather than an optimized
 * algorithm (Myers, Hirschberg, ...) — SPEC.md explicitly allows either, "only if it
 * produces the same script." A direct table is the simplest way to guarantee that byte
 * for byte, including the deletion-on-tie rule for repeated lines; trading O(n*m) time
 * and space for that guarantee is an acceptable tradeoff at this project's scale (a
 * teaching capstone, not a production-scale diff engine).
 */
object TextDiff {

  /**
   * Applies an edit script to old tokens per SPEC.md §4.4: retain copies old tokens,
   * delete consumes them without copying, insert emits new tokens directly. This is
   * `diff`'s inverse, used by tree materialization (plan units 5/7) to reconstruct a
   * text file's content from a patch's stored edit script.
   */
  def applyScript(oldTokens: Vector[String], script: Vector[EditOp]): Vector[String] = {
    val result = Vector.newBuilder[String]
    var i = 0
    script.foreach {
      case EditOp.Retain(n) =>
        result ++= oldTokens.slice(i, i + n.toInt)
        i += n.toInt
      case EditOp.Delete(n) =>
        i += n.toInt
      case EditOp.Insert(tokens) =>
        result ++= tokens
    }
    result.result()
  }

  def diff(oldTokens: Vector[String], newTokens: Vector[String]): Vector[EditOp] = {
    val n = oldTokens.length
    val m = newTokens.length

    // d(i)(j) = D(i, j) from SPEC.md §5, computed for the full grid.
    val d = Array.ofDim[Int](n + 1, m + 1)
    for (i <- n to 0 by -1)
      for (j <- m to 0 by -1)
        d(i)(j) =
          if (i == n && j == m) 0
          else if (i == n) m - j
          else if (j == m) n - i
          else if (oldTokens(i) == newTokens(j)) d(i + 1)(j + 1)
          else 1 + math.min(d(i + 1)(j), d(i)(j + 1))

    val raw = Vector.newBuilder[EditOp]
    var i = 0
    var j = 0
    while (i < n || j < m)
      if (i < n && j < m && oldTokens(i) == newTokens(j)) {
        raw += EditOp.Retain(1)
        i += 1
        j += 1
      } else if (j == m || (i < n && d(i + 1)(j) <= d(i)(j + 1))) {
        raw += EditOp.Delete(1)
        i += 1
      } else {
        raw += EditOp.Insert(Vector(newTokens(j)))
        j += 1
      }
    coalesce(raw.result())
  }

  /** SPEC.md §5 step 5: "Coalesce adjacent operations of the same kind." */
  private def coalesce(ops: Vector[EditOp]): Vector[EditOp] = {
    val result = Vector.newBuilder[EditOp]
    var i = 0
    while (i < ops.length)
      ops(i) match {
        case _: EditOp.Retain =>
          var count = 0L
          while (i < ops.length && ops(i).isInstanceOf[EditOp.Retain]) { count += 1; i += 1 }
          result += EditOp.Retain(count)
        case _: EditOp.Delete =>
          var count = 0L
          while (i < ops.length && ops(i).isInstanceOf[EditOp.Delete]) { count += 1; i += 1 }
          result += EditOp.Delete(count)
        case _: EditOp.Insert =>
          val tokens = Vector.newBuilder[String]
          while (i < ops.length && ops(i).isInstanceOf[EditOp.Insert]) {
            tokens ++= ops(i).asInstanceOf[EditOp.Insert].tokens
            i += 1
          }
          result += EditOp.Insert(tokens.result())
      }
    result.result()
  }
}
