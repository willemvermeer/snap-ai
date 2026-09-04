ThisBuild / scalaVersion := "2.13.6"
ThisBuild / organization := "snap"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
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
    Test / testOptions += Tests.Argument("-oD")
  )
