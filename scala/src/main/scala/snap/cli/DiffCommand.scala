package snap.cli

import snap.SnapError
import snap.replay.{TreeMaterializer, VersionResolution}
import snap.repository.RepositoryValidator
import snap.version.Version
import snap.workspace.{RepositoryFile, RepositoryOperand, WorkingTree, WorkingTreeStatus}

/**
 * `snap diff [<old> <new> [--repo <repository>]]` (SPEC.md §7.6). Grammar is exactly
 * zero, two, or four (with a literal `--repo` third) operands; anything else is this
 * command's own usage error, distinct from the generic grammar error.
 */
object DiffCommand {
  def run(args: Vector[String], env: Cli.Env): Unit = args.toList match {
    case Nil => diffWorkingTree(env)
    case oldStr :: newStr :: Nil => diffVersions(env, oldStr, newStr, None)
    case oldStr :: newStr :: "--repo" :: repoOperand :: Nil =>
      diffVersions(env, oldStr, newStr, Some(repoOperand))
    case _ => usageError()
  }

  private def usageError(): Nothing =
    throw SnapError("usage: snap diff [<old> <new> [--repo <repository>]]")

  private def diffWorkingTree(env: Cli.Env): Unit = {
    val snapDir = CliSupport.requireSnapDir(env)
    val repository = RepositoryFile.read(snapDir)
    val current = TreeMaterializer.materialize(repository)
    val working = WorkingTree.scan(snapDir.getParent)
    renderDiffs(env, current, working)
  }

  /**
   * SPEC.md §7.6: "old" always resolves against the local repository; "new" resolves
   * against `--repo`'s repository when given, "without importing it" — no patches are
   * merged, `new` is just resolved against that other repository's own patch set.
   */
  private def diffVersions(
    env: Cli.Env,
    oldStr: String,
    newStr: String,
    repoOperand: Option[String]
  ): Unit = {
    val oldVersion = Version.parseCanonical(oldStr)
    val newVersion = Version.parseCanonical(newStr)
    val snapDir = CliSupport.requireSnapDir(env)
    val localRepo = RepositoryFile.read(snapDir)

    def resolveOrFail(version: Version, patches: Vector[snap.repository.Patch]) =
      VersionResolution
        .resolve(version, patches)
        .getOrElse(throw SnapError(s"unknown version: ${version.toCanonicalString}"))

    val oldPatches = resolveOrFail(oldVersion, localRepo.patches)
    val newPatches = repoOperand match {
      case None => resolveOrFail(newVersion, localRepo.patches)
      case Some(operand) =>
        val otherRepo = RepositoryOperand.resolve(operand, env.cwd)
        RepositoryValidator.checkNoCollisions(localRepo.patches, otherRepo.patches)
        resolveOrFail(newVersion, otherRepo.patches)
    }

    renderDiffs(
      env,
      TreeMaterializer.materializeOrdered(oldPatches),
      TreeMaterializer.materializeOrdered(newPatches)
    )
  }

  private def renderDiffs(
    env: Cli.Env,
    oldTree: Map[String, Vector[Byte]],
    newTree: Map[String, Vector[Byte]]
  ): Unit = {
    val terminal = env.presentation.stdout
    WorkingTreeStatus.compare(oldTree, newTree).foreach { case (path, _) =>
      env.stdout.print(DiffRenderer.render(path, oldTree.get(path), newTree.get(path), terminal))
    }
  }
}
