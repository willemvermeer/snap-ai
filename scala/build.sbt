ThisBuild / scalaVersion := "2.13.6"
ThisBuild / organization := "snap"
ThisBuild / version := "0.1.0"

// Static bug-pattern analysis: no actively-maintained sbt plugin for SpotBugs (the
// maintained successor to the long-dead FindBugs) resolves on this sbt 1.5.1 / Scala
// 2.13.6 toolchain (see AGENTS.md / task notes for the empirical research). Instead we
// depend on the real, current SpotBugs engine directly in a hidden ivy configuration and
// drive it from a small custom task that forks SpotBugs' own textui entry point.
lazy val SpotBugs = config("spotbugs").hide

lazy val spotbugs =
  taskKey[File]("Run SpotBugs static analysis against the compiled classes and write an XML report")

lazy val root = (project in file("."))
  .configs(SpotBugs)
  .settings(
    name := "snap",
    Compile / mainClass := Some("snap.Main"),
    assembly / mainClass := Some("snap.Main"),
    assembly / assemblyJarName := s"${name.value}-assembly-${version.value}.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("module-info.class")              => MergeStrategy.discard
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    scalacOptions ++= Seq(
      "-deprecation",
      "-unchecked",
      "-feature",
      "-Xlint"
    ),
    libraryDependencies += "org.json4s" %% "json4s-jackson" % "4.0.7",
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    Test / testOptions += Tests.Argument("-oD"),
    coverageMinimumStmtTotal := 95,
    coverageMinimumBranchTotal := 95,
    coverageFailOnMinimum := true,
    coverageHighlighting := true,
    // Pure process wiring (argv/env/stdio/exit code) with no branching logic of its own;
    // it's exercised end to end by the real acceptance suite running the built jar, not
    // meaningfully unit-testable without spawning a live subprocess.
    coverageExcludedPackages := "snap\\.Main",
    ivyConfigurations += SpotBugs,
    libraryDependencies += "com.github.spotbugs" % "spotbugs" % "4.9.3" % SpotBugs.name,
    spotbugs := {
      val log = streams.value.log
      val classesDir = (Compile / classDirectory).value
      val auxClasspath = (Compile / dependencyClasspath).value.files
      val toolClasspath = update.value.select(configurationFilter(SpotBugs.name))
      val reportDir = target.value / "spotbugs"
      IO.createDirectory(reportDir)
      val reportFile = reportDir / "spotbugs-report.xml"
      val javaBin = sys.props("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java"
      val args = Seq(
        "-cp",
        toolClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
        "edu.umd.cs.findbugs.LaunchAppropriateUI",
        "-textui",
        "-effort:default",
        "-xml:withMessages",
        "-output",
        reportFile.getAbsolutePath,
        "-auxclasspath",
        auxClasspath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
        classesDir.getAbsolutePath
      )
      log.info(s"Running SpotBugs against $classesDir")
      val exitCode = scala.sys.process.Process(javaBin +: args).!
      // SpotBugs' textui exits non-zero when bugs are found (not just on tool errors),
      // so we only fail the sbt task if it couldn't produce a report at all.
      if (!reportFile.exists()) {
        sys.error(s"SpotBugs failed to produce a report (exit code $exitCode)")
      } else {
        log.info(s"SpotBugs exited with code $exitCode; report written to ${reportFile.getAbsolutePath}")
      }
      reportFile
    }
  )
