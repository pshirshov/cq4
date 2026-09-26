ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.7mind.cq"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked")

val izumiVersion = "1.2.25"
val circeVersion = "0.14.16"
val http4sVersion = "0.23.37"
val runtimeClasspath = taskKey[String]("Resolved runtime classpath for JVM and native launchers")

lazy val contracts = project.in(file("contracts")).settings(
  Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "generated" / "scala",
  libraryDependencies += "io.circe" %% "circe-parser" % circeVersion,
)

lazy val core = project.in(file("core")).dependsOn(contracts).settings(
  libraryDependencies ++= Seq(
    "io.7mind.izumi" %% "fundamentals-bio" % izumiVersion,
    "io.7mind.izumi" %% "distage-core" % izumiVersion,
    "dev.zio" %% "zio" % "2.1.26",
  ),
)

lazy val server = project.in(file("server")).dependsOn(core).settings(
  runtimeClasspath := {
    val converter = fileConverter.value
    (Runtime / fullClasspath).value.map(entry => converter.toPath(entry.data).toString).mkString(java.io.File.pathSeparator)
  },
  libraryDependencies ++= Seq(
    "io.7mind.izumi" %% "distage-framework" % izumiVersion,
    "io.7mind.izumi" %% "fundamentals-bio" % izumiVersion,
    "dev.zio" %% "zio-interop-cats" % "23.1.0.13",
    "org.http4s" %% "http4s-ember-server" % http4sVersion,
    "org.http4s" %% "http4s-dsl" % http4sVersion,
    "org.postgresql" % "postgresql" % "42.7.13",
    "io.7mind.izumi" %% "distage-testkit-scalatest" % izumiVersion % Test,
    "org.scalatest" %% "scalatest" % "3.2.20" % Test,
  ),
  Compile / mainClass := Some("cq.server.Main"),
  Compile / run / fork := true,
  Test / fork := true,
)

lazy val root = project.in(file(".")).aggregate(contracts, core, server).settings(
  publish / skip := true,
)
