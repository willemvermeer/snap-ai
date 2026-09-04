package snap.cli

import snap.SnapError
import snap.replay.ReplayEngine
import snap.repository.{Repository, RepositoryValidator}
import snap.workspace.{RepositoryFile, RepositoryOperand, TreeInstaller, WorkingTree}

/**
 * `snap merge <repository>` (SPEC.md §7.8). Grammar is exactly one operand. Unlike
 * `commit`/`revert`, no contributor configuration is required — merge authors no patch.
 */
object MergeCommand {
  def run(args: Vector[String], env: Cli.Env): Unit = {
    val operand = args match {
      case Vector(r) => r
      case _ => throw SnapError("invalid command or arguments")
    }

    val snapDir = CliSupport.requireSnapDir(env)
    val local = RepositoryFile.read(snapDir)
    val other = RepositoryOperand.resolve(operand, env.cwd)

    RepositoryValidator.checkNoCollisions(local.patches, other.patches)
    val unionedPatches = (local.patches.map(p => p.dot -> p) ++ other.patches.map(p =>
      p.dot -> p
    )).toMap.values.toVector
      .sortBy(_.dot)
    val joinedFrontier = local.frontier.join(other.frontier)
    val joined = RepositoryValidator.validate(joinedFrontier, unionedPatches)

    if (joined.frontier == local.frontier) {
      // SPEC.md §7.8: "Merging equal or already-contained history succeeds, changes
      // nothing, emits no warnings, and prints the unchanged version" — no working-tree
      // check needed either, since there is nothing to install.
      env.stdout.print(
        Rendering.success(env.presentation.stdout, "Merged", joined.frontier.toCanonicalString)
      )
    } else {
      val current = ReplayEngine.replay(RepositoryValidator.integrationOrder(local.patches))
      val working = WorkingTree.scan(snapDir.getParent)
      if (current.tree != working) throw SnapError("working tree is dirty")

      val joinedReplay = ReplayEngine.replay(RepositoryValidator.integrationOrder(joined.patches))
      val newWarnings = joinedReplay.warnings.filterNot(current.warnings.toSet)

      TreeInstaller.install(snapDir.getParent, current.tree, joinedReplay.tree)
      RepositoryFile.write(snapDir, Repository(joined.frontier, joined.patches))

      env.stdout.print(
        Rendering.success(env.presentation.stdout, "Merged", joined.frontier.toCanonicalString)
      )
      newWarnings.foreach { case (path, reason) =>
        env.stderr.print(
          Rendering.warning(env.presentation.stderr, s"auto-resolved $path: $reason")
        )
      }
    }
  }
}
