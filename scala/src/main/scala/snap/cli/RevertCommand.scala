package snap.cli

import java.nio.file.Path
import snap.SnapError
import snap.config.Config
import snap.replay.{TreeMaterializer, VersionResolution}
import snap.repository.{Patch, Repository}
import snap.version.Version
import snap.workspace.{RepositoryFile, TreeInstaller, WorkingTree, WorkingTreeStatus}

/** `snap revert <version>` (SPEC.md §7.7). Grammar is exactly one operand. */
object RevertCommand {
  def run(args: Vector[String], env: Cli.Env): Unit = {
    val versionArg = args match {
      case Vector(v) => v
      case _ => throw SnapError("invalid command or arguments")
    }
    val targetVersion = Version.parseCanonical(versionArg)

    val snapDir = CliSupport.requireSnapDir(env)
    val author = Config.resolveContributorId(Some(snapDir), env.vars.get("HOME").map(Path.of(_)))
    val repository = RepositoryFile.read(snapDir)

    val current = TreeMaterializer.materialize(repository)
    val working = WorkingTree.scan(snapDir.getParent)
    if (current != working) throw SnapError("working tree is dirty")

    val targetPatches = VersionResolution
      .resolve(targetVersion, repository.patches)
      .getOrElse(throw SnapError(s"unknown version: ${targetVersion.toCanonicalString}"))
    val target = TreeMaterializer.materializeOrdered(targetPatches)

    if (current == target) throw SnapError("target tree is already current")

    val changes = WorkingTreeStatus.compare(current, target).map { case (path, _) =>
      ChangeBuilder.build(path, current.get(path), target.get(path))
    }
    val revision = repository.frontier.revisionOf(author) + 1
    val message = s"revert to ${targetVersion.toCanonicalString}"
    val patch = Patch(author, revision, repository.frontier, message, changes)
    val updated = Repository(patch.resultVersion, (repository.patches :+ patch).sortBy(_.dot))

    // SPEC.md §10: working files are updated before repository.json is replaced.
    TreeInstaller.install(snapDir.getParent, current, target)
    RepositoryFile.write(snapDir, updated)
    env.stdout.print(s"${patch.resultVersion.toCanonicalString}\n")
  }
}
