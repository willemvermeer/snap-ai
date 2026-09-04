package snap.repository

/**
 * A single-path change within a patch, SPEC.md §4.3. `content` is stored as a `Vector`
 * (not `Array`) so `Change`/`Patch` retain structural equality — important for §3.5's
 * "same dot, structurally different patches is corruption" comparison.
 */
sealed trait Change { def path: String }
object Change {
  final case class Text(path: String, edit: Vector[EditOp]) extends Change
  final case class Put(path: String, content: Vector[Byte]) extends Change
  final case class Delete(path: String) extends Change
}
