import sbt.url
import sbtrelease.ReleaseStateTransformations._

name := "tresor"

scalaVersion := "2.12.8"

scalacOptions += "-Ypartial-unification"

crossScalaVersions := Seq(scalaVersion.value, "2.11.11")
organization := "com.drobisch"

releasePublishArtifactsAction := PgpKeys.publishSigned.value
releaseCrossBuild := true

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  publishArtifacts,
  setNextVersion,
  commitNextVersion,
  releaseStepCommandAndRemaining("sonatypeReleaseAll"),
  pushChanges
)

publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)

publishMavenStyle := true
licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
homepage := Some(url("https://github.com/adrobisch/tresor"))
scmInfo := Some(
  ScmInfo(
    url("https://github.com/adrobisch/tresor.git"),
    "scm:git@github.com:adrobisch/tresor.git"
  )
)

developers := List(
  Developer(id = "adrobisch", name = "Andreas Drobisch", email = "github@drobisch.com", url = url("http://drobisch.com/"))
)

val circeVersion = "0.10.0"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion % Provided)

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "1.2.0",
  "com.softwaremill.sttp" %% "core" % "1.5.7",
  "org.slf4j" % "slf4j-api" % "1.7.25" % Provided,
  "org.apache.logging.log4j" % "log4j-api" % "2.11.1" % Test,
  "org.apache.logging.log4j" % "log4j" % "2.11.1" % Test,
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.11.1" % Test,
  "org.scalatest" %% "scalatest" % "3.0.5" % Test,
  "com.github.tomakehurst" % "wiremock-standalone" % "2.19.0" % Test
)
