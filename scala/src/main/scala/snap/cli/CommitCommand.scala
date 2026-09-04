package snap.cli

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import snap.SnapError
import snap.config.Config
import snap.diff.{TextDiff, TextTokens}
import snap.replay.TreeMaterializer
import snap.repository.{Change, Patch, Repository}
import snap.workspace.{PathStatus, RepositoryFile, WorkingTree, WorkingTreeStatus}

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

    Patch.validateMessage(message)
    if (message.getBytes(UTF_8).length > MaxMessageBytes) {
      throw SnapError(s"commit message exceeds $MaxMessageBytes bytes")
    }

    val repository = RepositoryFile.read(snapDir)
    val current = TreeMaterializer.materialize(repository)
    val working = WorkingTree.scan(snapDir.getParent)
    val diffs = WorkingTreeStatus.compare(current, working)
    if (diffs.isEmpty) throw SnapError("working tree is clean")

    val changes = diffs.map { case (path, status) => buildChange(path, status, current, working) }
    val revision = repository.frontier.revisionOf(author) + 1
    val patch = Patch(author, revision, repository.frontier, message, changes)
    val updated = Repository(patch.resultVersion, (repository.patches :+ patch).sortBy(_.dot))

    RepositoryFile.write(snapDir, updated)
    env.stdout.print(s"${patch.resultVersion.toCanonicalString}\n")
  }

  /**
   * SPEC.md §7.5: "Uses a text change when the new content is text and the old path is
   * absent or text. Otherwise it uses put; removed paths use delete."
   */
  private def buildChange(
    path: String,
    status: PathStatus,
    current: Map[String, Vector[Byte]],
    working: Map[String, Vector[Byte]]
  ): Change = status match {
    case PathStatus.Deleted => Change.Delete(path)
    case PathStatus.Added | PathStatus.Modified =>
      val newBytes = working(path)
      val oldBytesOpt = current.get(path)
      val newIsText = TextTokens.isText(newBytes.toArray)
      val oldIsTextOrAbsent = oldBytesOpt.forall(b => TextTokens.isText(b.toArray))
      if (newIsText && oldIsTextOrAbsent) {
        val oldTokens = oldBytesOpt match {
          case Some(bytes) => TextTokens.tokenize(TextTokens.toText(bytes.toArray))
          case None => Vector.empty
        }
        val newTokens = TextTokens.tokenize(TextTokens.toText(newBytes.toArray))
        Change.Text(path, TextDiff.diff(oldTokens, newTokens))
      } else {
        Change.Put(path, newBytes)
      }
  }
}
