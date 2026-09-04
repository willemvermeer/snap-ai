package snap.cli

import snap.SnapError
import snap.replay.TreeMaterializer
import snap.workspace.{PathStatus, RepositoryFile, WorkingTree, WorkingTreeStatus}

/**
 * `snap status` (SPEC.md §7.3): the current version, then working-tree changes sorted
 * by path. No operands or options.
 */
object StatusCommand {
  def run(args: Vector[String], env: Cli.Env): Unit = {
    if (args.nonEmpty) throw SnapError("invalid command or arguments")

    val snapDir = CliSupport.requireSnapDir(env)
    val repository = RepositoryFile.read(snapDir)
    val current = TreeMaterializer.materialize(repository)
    val working = WorkingTree.scan(snapDir.getParent)
    val changes = WorkingTreeStatus.compare(current, working)

    env.stdout.print(s"version ${repository.frontier.toCanonicalString}\n")
    changes.foreach { case (path, status) =>
      val code = status match {
        case PathStatus.Added => "A"
        case PathStatus.Modified => "M"
        case PathStatus.Deleted => "D"
      }
      env.stdout.print(s"$code $path\n")
    }
  }
}
