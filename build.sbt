name := "tresor"

version := "0.1.0-SNAPSHOT"

scalaVersion := "2.12.8"

crossScalaVersions := Seq(scalaVersion.value, "2.11.11")

val circeVersion = "0.10.0"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "1.2.0",
  "com.softwaremill.sttp" %% "core" % "1.5.7",
  "org.scalatest" %% "scalatest" % "3.0.5" % Test
)