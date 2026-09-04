package snap.cli

import snap.SnapError
import snap.repository.RepositoryValidator
import snap.workspace.RepositoryFile

/**
 * `snap log` (SPEC.md §7.4): patches in reverse canonical integration order, one
 * tab-separated line each. No operands or options.
 */
object LogCommand {
  def run(args: Vector[String], env: Cli.Env): Unit = {
    if (args.nonEmpty) throw SnapError("invalid command or arguments")

    val snapDir = CliSupport.requireSnapDir(env)
    val repository = RepositoryFile.read(snapDir)
    val ordered = RepositoryValidator.integrationOrder(repository.patches)
    ordered.reverse.foreach { patch =>
      env.stdout.print(
        s"${patch.resultVersion.toCanonicalString}\t${patch.author}\t${escape(patch.message)}\n"
      )
    }
  }

  /**
   * SPEC.md §7.4: "backslash, tab, and LF are escaped as \\, \t, and \n in that order" —
   * backslash first, so escaping tab/LF afterward can't accidentally double-escape the
   * backslashes those steps introduce.
   */
  private def escape(message: String): String =
    message.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
}
