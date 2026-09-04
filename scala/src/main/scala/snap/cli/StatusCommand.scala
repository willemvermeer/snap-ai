package snap.cli

import snap.SnapError
import snap.replay.TreeMaterializer
import snap.workspace.{RepositoryFile, WorkingTree, WorkingTreeStatus}

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
    val terminal = env.presentation.stdout

    env.stdout.print(Rendering.statusHeader(terminal, repository.frontier.toCanonicalString))
    if (terminal && changes.isEmpty) env.stdout.print(Rendering.statusClean(terminal))
    changes.foreach { case (path, status) =>
      env.stdout.print(Rendering.statusRow(terminal, status, path))
    }
  }
}
