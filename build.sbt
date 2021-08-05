import sbt.url

name := "tresor"

scalaVersion := "2.13.6"

inThisBuild(List(
  organization := "com.drobisch",
  homepage := Some(url("https://github.com/adrobisch/tresor")),
  licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
  developers := List(
    Developer(id = "adrobisch", name = "Andreas Drobisch", email = "github@drobisch.com", url = url("http://drobisch.com/"))
  ),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/adrobisch/tresor"),
      "scm:git@github.com:adrobisch/tresor.git"
    )
  )
))

val circeVersion = "0.13.0"

val circeDeps = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)

libraryDependencies ++= circeDeps.map(_ % Provided)

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "2.5.1" % Provided,
  "com.softwaremill.sttp.client3" %% "core" % "3.3.6",
  "org.slf4j" % "slf4j-api" % "1.7.25" % Provided,

  "org.scalatest" %% "scalatest" % "3.2.9" % Test,
  "org.apache.logging.log4j" % "log4j-api" % "2.14.1" % Test,
  "org.apache.logging.log4j" % "log4j" % "2.14.1" % Test,
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.14.1" % Test,
  "com.github.tomakehurst" % "wiremock-standalone" % "2.27.2" % Test
)
