package snap.cli

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import snap.SnapError
import snap.config.Config
import snap.replay.TreeMaterializer
import snap.repository.{Patch, Repository}
import snap.workspace.{RepositoryFile, WorkingTree, WorkingTreeStatus}

/** `snap commit <message>` (SPEC.md §7.5). Grammar is exactly one operand. */
object CommitCommand {
  private val MaxMessageBytes = 4096

  def run(args: Vector[String], env: Cli.Env): Unit = {
    val message = args match {
      case Vector(m) => m
      case _ => throw SnapError("invalid command or arguments")
    }

    val snapDir = CliSupport.requireSnapDir(env)
    val author = Config.resolveContributorId(Some(snapDir), env.vars.get("HOME").map(Path.of(_)))

    if (!Patch.isValidMessage(message)) throw SnapError("invalid commit message")
    if (message.getBytes(UTF_8).length > MaxMessageBytes) {
      throw SnapError("invalid commit message")
    }

    val repository = RepositoryFile.read(snapDir)
    val current = TreeMaterializer.materialize(repository)
    val working = WorkingTree.scan(snapDir.getParent)
    val diffs = WorkingTreeStatus.compare(current, working)
    if (diffs.isEmpty) throw SnapError("working tree is clean")

    val changes = diffs.map { case (path, _) =>
      ChangeBuilder.build(path, current.get(path), working.get(path))
    }
    val revision = repository.frontier.revisionOf(author) + 1
    val patch = Patch(author, revision, repository.frontier, message, changes)
    val updated = Repository(patch.resultVersion, (repository.patches :+ patch).sortBy(_.dot))

    RepositoryFile.write(snapDir, updated)
    env.stdout.print(
      Rendering.success(env.presentation.stdout, "Committed", patch.resultVersion.toCanonicalString)
    )
  }
}
