package snap.cli

import java.nio.file.Path
import snap.SnapError
import snap.workspace.RepoLocator

private[cli] object CliSupport {
  def requireSnapDir(env: Cli.Env): Path =
    RepoLocator.locate(env.cwd).getOrElse(throw SnapError("not a Snap repository"))
}
