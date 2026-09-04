package snap

/** Expected, user-facing failure. Formatted as `snap: <message>` and exits 1. */
final case class SnapError(message: String) extends RuntimeException(message)
