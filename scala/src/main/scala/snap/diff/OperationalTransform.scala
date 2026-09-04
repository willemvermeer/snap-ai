package snap.diff

import snap.repository.EditOp

/**
 * SPEC.md §6.3: transforms incoming edit `P` so it applies after aggregate context edit
 * `Q`, per the exact op-pair table there. Retain/delete operate on base-token counts and
 * are processed at single-token granularity here (splitting a `Retain(n)`/`Delete(n)`
 * into `n` unit ops) so the table's row-by-row walk needs no separate "split the smaller
 * count off the larger" bookkeeping; insert ops are never split, matching "`length(Q
 * insert)` is its token count" treating a whole insert as one step.
 */
object OperationalTransform {

  def transform(p: Vector[EditOp], q: Vector[EditOp]): Vector[EditOp] = {
    val pFlat = flatten(p)
    val qFlat = flatten(q)
    val raw = Vector.newBuilder[EditOp]
    var pi = 0
    var qi = 0

    while (pi < pFlat.length || qi < qFlat.length) {
      val qHere = if (qi < qFlat.length) Some(qFlat(qi)) else None
      qHere match {
        // "The Q insert row has priority" — checked before looking at P at all.
        case Some(EditOp.Insert(tokens)) =>
          raw += EditOp.Retain(tokens.length.toLong)
          qi += 1
        case _ =>
          val pHere = if (pi < pFlat.length) Some(pFlat(pi)) else None
          pHere match {
            case Some(EditOp.Insert(tokens)) =>
              raw += EditOp.Insert(tokens)
              pi += 1
            case Some(EditOp.Retain(_)) =>
              qHere match {
                case Some(EditOp.Retain(_)) => raw += EditOp.Retain(1); pi += 1; qi += 1
                case Some(EditOp.Delete(_)) => pi += 1; qi += 1
                case _ =>
                  throw new IllegalStateException("OT: P has a pending retain but Q is exhausted")
              }
            case Some(EditOp.Delete(_)) =>
              qHere match {
                case Some(EditOp.Retain(_)) => raw += EditOp.Delete(1); pi += 1; qi += 1
                case Some(EditOp.Delete(_)) => pi += 1; qi += 1
                case _ =>
                  throw new IllegalStateException("OT: P has a pending delete but Q is exhausted")
              }
            case None =>
              // Both exhausted; the while condition prevents reaching this.
              throw new IllegalStateException("OT: both P and Q exhausted")
          }
      }
    }
    TextDiff.coalesce(raw.result())
  }

  private def flatten(ops: Vector[EditOp]): Vector[EditOp] = ops.flatMap {
    case EditOp.Retain(n) => Vector.fill(n.toInt)(EditOp.Retain(1))
    case EditOp.Delete(n) => Vector.fill(n.toInt)(EditOp.Delete(1))
    case insert: EditOp.Insert => Vector(insert)
  }
}
