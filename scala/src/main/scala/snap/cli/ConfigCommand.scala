package snap.cli

import java.nio.file.Path
import snap.SnapError
import snap.config.Config
import snap.repo.RepoLocator

/**
 * `snap config [--global] contributor.id <id>` (SPEC.md §7.2). Grammar is exactly
 * `config [--global] contributor.id <id>`; anything else is the generic grammar error.
 * The command never reads the existing config file — it always writes a fresh one, so
 * unknown fields in a prior file are simply dropped, and a malformed prior file does not
 * block writing a new value.
 */
object ConfigCommand {
  def run(args: Vector[String], env: Cli.Env): Unit = {
    val (global, rest) = args match {
      case "--global" +: tail => (true, tail)
      case _ => (false, args)
    }
    rest match {
      case Vector("contributor.id", id) =>
        Config.write(targetPath(global, env), id)
      case _ =>
        throw SnapError("invalid command or arguments")
    }
  }

  private def targetPath(global: Boolean, env: Cli.Env): Path =
    if (global) {
      val home = env.vars.getOrElse("HOME", throw SnapError("HOME is not set"))
      Config.globalPath(Path.of(home))
    } else {
      val snapDir = RepoLocator.locate(env.cwd).getOrElse(throw SnapError("not a Snap repository"))
      Config.localPath(snapDir)
    }
}
