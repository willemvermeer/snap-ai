package snap.cli

import java.nio.file.Files
import snap.SnapError
import snap.repository.Repository
import snap.version.Version
import snap.workspace.{RepoLocator, RepositoryFile}

/**
 * `snap init [path]` (SPEC.md §7.1). Grammar is exactly one optional plain (non-`-`)
 * operand.
 */
object InitCommand {
  def run(args: Vector[String], env: Cli.Env): Unit = {
    val pathArg = args match {
      case Vector() => "."
      case Vector(p) if !p.startsWith("-") => p
      case _ => throw SnapError("invalid command or arguments")
    }
    val target = env.cwd.resolve(pathArg).normalize()

    if (Files.isDirectory(target.resolve(".snap"))) {
      throw SnapError("repository already exists")
    }
    // Reaching here means `target` itself has no `.snap`, so anything `locate` still
    // finds must come from an enclosing ancestor.
    if (RepoLocator.locate(target).isDefined) {
      throw SnapError("cannot initialize inside repository")
    }

    Files.createDirectories(target)
    val snapDir = Files.createDirectory(target.resolve(".snap"))
    RepositoryFile.write(snapDir, Repository(Version.Empty, Vector.empty))
    env.stdout.print("()\n")
  }
}
