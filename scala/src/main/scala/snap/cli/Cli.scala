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

  // Not part of SPEC.md §7's command grammar — a deliberate extension so `--help`/`-h`
  // give a way to see the grammar without consulting the spec. Only the bare top-level
  // form is recognized; extra arguments fall through to the generic grammar error, same
  // as `--version extra` does.
  private val Usage =
    """usage: snap <command> [<args>]
      |
      |  snap init [path]                                    create a repository
      |  snap config [--global] contributor.id <id>           set the contributor id
      |  snap status                                          show working tree status
      |  snap log                                             show commit history
      |  snap commit <message>                                record a new version
      |  snap diff [<old> <new> [--repo <repository>]]        compare versions
      |  snap revert <version>                                restore a prior version
      |  snap merge <repository>                              merge another repository
      |  snap --serve [port]                                  serve the repository over HTTP
      |  snap --version                                       print the version
      |  snap --help                                          print this help
      |""".stripMargin

  private def dispatch(args: Vector[String], env: Env): Unit = args.toList match {
    case "--version" :: Nil =>
      env.stdout.print(s"snap $Version\n")
    case ("--help" | "-h") :: Nil =>
      env.stdout.print(Usage)
    case "config" :: rest =>
      ConfigCommand.run(rest.toVector, env)
    case "init" :: rest =>
      InitCommand.run(rest.toVector, env)
    case "status" :: rest =>
      StatusCommand.run(rest.toVector, env)
    case "log" :: rest =>
      LogCommand.run(rest.toVector, env)
    case "commit" :: rest =>
      CommitCommand.run(rest.toVector, env)
    case "diff" :: rest =>
      DiffCommand.run(rest.toVector, env)
    case "revert" :: rest =>
      RevertCommand.run(rest.toVector, env)
    case "merge" :: rest =>
      MergeCommand.run(rest.toVector, env)
    case _ =>
      throw SnapError("invalid command or arguments")
  }
}
