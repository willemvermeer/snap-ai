package snap.cli

import java.io.PrintStream
import java.nio.file.Path
import snap.SnapError

/**
 * Top-level CLI entry point: resolves presentation, dispatches to a command, and
 * converts exceptions to the stable exit-code channels of SPEC.md §10 — success 0,
 * expected errors (`SnapError`) 1, unexpected internal failures 2.
 */
object Cli {
  val Version = "0.1.0"

  /**
   * Everything a command needs, explicit rather than ambient, so commands are testable
   * without touching real stdio, the real environment, or the real working directory.
   */
  final case class Env(
    vars: Map[String, String],
    cwd: Path,
    stdout: PrintStream,
    stderr: PrintStream,
    stdoutIsTty: Boolean,
    stderrIsTty: Boolean
  )

  def run(args: Vector[String], env: Env): Int =
    Presentation.resolve(env.vars, env.stdoutIsTty, env.stderrIsTty) match {
      case Left(err) =>
        // "This error itself is plain because no valid presentation was selected." (§7.11)
        env.stderr.print(s"snap: $err\n")
        1
      case Right(_) =>
        try {
          dispatch(args, env)
          0
        } catch {
          case SnapError(message) =>
            env.stderr.print(s"snap: $message\n")
            1
          case ex: Throwable =>
            env.stderr.print(s"snap: internal error: ${ex.getMessage}\n")
            2
        }
    }

  private def dispatch(args: Vector[String], env: Env): Unit = args.toList match {
    case "--version" :: Nil =>
      env.stdout.print(s"snap $Version\n")
    case "config" :: rest =>
      ConfigCommand.run(rest.toVector, env)
    case _ =>
      throw SnapError("invalid command or arguments")
  }
}
