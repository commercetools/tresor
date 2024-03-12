import sbt.url

name := "tresor"

scalaVersion := "2.13.13"

scalacOptions ++= Seq(
  "-encoding",
  "utf8", // Option and arguments on same line
  "-Xfatal-warnings", // New lines for each options
  "-deprecation",
  "-unchecked",
  "-language:implicitConversions",
  "-language:higherKinds",
  "-language:existentials",
  "-language:postfixOps"
)

inThisBuild(
  List(
    organization := "com.commercetools",
    homepage := Some(url("https://github.com/commercetools/tresor")),
    licenses := Seq(
      "APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")
    ),
    developers := List(
      Developer(
        id = "adrobisch",
        name = "Andreas Drobisch",
        email = "github@drobisch.com",
        url = url("http://drobisch.com/")
      )
    ),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/commercetools/tresor"),
        "scm:git@github.com:commercetools/tresor.git"
      )
    )
  )
)

val circeVersion = "0.14.6"

val circeDeps = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)

libraryDependencies ++= circeDeps ++ Seq(
  "org.typelevel" %% "cats-effect" % "3.5.4",
  "com.softwaremill.sttp.client3" %% "core" % "3.9.4",
  "org.slf4j" % "slf4j-api" % "2.0.12",
  "org.scalatest" %% "scalatest" % "3.2.18" % Test,
  "org.apache.logging.log4j" % "log4j-api" % "2.23.1" % Test,
  "org.apache.logging.log4j" % "log4j" % "2.23.1" % Test,
  "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.23.1" % Test,
  "com.github.tomakehurst" % "wiremock-standalone" % "3.0.1" % Test
)
