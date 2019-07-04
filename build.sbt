import sbt.url
import sbtrelease.ReleaseStateTransformations._

name := "tresor"

scalaVersion := "2.12.8"

scalacOptions ++= (if (scalaVersion.value startsWith "2.13.") Seq.empty else Seq("-Ypartial-unification"))

crossScalaVersions := Seq(scalaVersion.value, "2.13.0")
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
    url("https://github.com/adrobisch/tresor"),
    "scm:git@github.com:adrobisch/tresor.git"
  )
)

developers := List(
  Developer(id = "adrobisch", name = "Andreas Drobisch", email = "github@drobisch.com", url = url("http://drobisch.com/"))
)

val circeVersion = "0.12.0-M4"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion % Provided)

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "2.0.0-M4" % Provided,
  "com.softwaremill.sttp" %% "core" % "1.6.0",
  "org.scalatest" %% "scalatest" % "3.0.8" % Test,

  "org.slf4j" % "slf4j-api" % "1.7.25" % Provided,
  "org.apache.logging.log4j" % "log4j-api" % "2.11.1" % Test,
  "org.apache.logging.log4j" % "log4j" % "2.11.1" % Test,
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.11.1" % Test,
  "com.github.tomakehurst" % "wiremock-standalone" % "2.19.0" % Test
)
