package snap

import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Paths
import snap.cli.Cli

object Main {
  def main(args: Array[String]): Unit = {
    val stdout = new PrintStream(System.out, true, UTF_8)
    val stderr = new PrintStream(System.err, true, UTF_8)
    val isTty = System.console() != null // TODO(unit 10): detect stdout/stderr independently.

    val env = Cli.Env(
      vars = sys.env,
      cwd = Paths.get("").toAbsolutePath.normalize(),
      stdout = stdout,
      stderr = stderr,
      stdoutIsTty = isTty,
      stderrIsTty = isTty
    )

    val exitCode = Cli.run(args.toVector, env)
    stdout.flush()
    stderr.flush()
    sys.exit(exitCode)
  }
}
