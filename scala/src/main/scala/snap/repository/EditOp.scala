package snap.repository

/**
 * One text edit-script operation, SPEC.md §4.4. `Retain`/`Delete` counts and `Insert`
 * token lists are validated positive/nonempty by the codec at decode time — this type
 * itself carries no further invariant.
 */
sealed trait EditOp
object EditOp {
  final case class Retain(count: Long) extends EditOp
  final case class Delete(count: Long) extends EditOp
  final case class Insert(tokens: Vector[String]) extends EditOp
}
