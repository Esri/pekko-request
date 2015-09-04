enablePlugins(JavaAppPackaging)

name := "akka-request"
organization := "io.dronekit"
version := "0.2"
scalaVersion := "2.11.7"

libraryDependencies ++= {
  val akkaV = "2.3.12"
  val akkaStreamV = "1.0"
  val scalaTestV = "2.2.4"
  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream-experimental" % akkaStreamV,
    "com.typesafe.akka" %% "akka-http-core-experimental" % akkaStreamV,
    "com.typesafe.akka" %% "akka-http-experimental" % akkaStreamV,
    "com.typesafe.akka" %% "akka-http-spray-json-experimental" % akkaStreamV,
    "io.spray" %%  "spray-json" % "1.3.2",
    "commons-codec" % "commons-codec" % "1.6",
    "com.typesafe.akka" %% "akka-http-testkit-experimental" % akkaStreamV,
    "io.dronekit" %% "oauth-headers" % "0.1",
    "org.scalatest" %% "scalatest" % "2.2.4" % "test")
}
