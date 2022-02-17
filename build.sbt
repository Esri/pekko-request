name := "akka-request"
organization := "io.dronekit"
licenses += "ISC" -> url("https://opensource.org/licenses/ISC")
version := "4.1.1"

scalaVersion := "2.13.8"
scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  val akkaV = "2.6.18"
  val akkaHttpV = "10.2.8"
  
  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    
    "com.typesafe.akka" %% "akka-http-core" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,

    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
    
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % Test,
    "de.heikoseeberger" %% "akka-http-play-json" % "1.27.0" % Test,
    "com.typesafe.play" %% "play-json" % "2.8.2" % Test,
    "org.scalatest" %% "scalatest" % "3.2.11" % Test,
    "ch.qos.logback" % "logback-classic" % "1.2.10" % Test
  )
}
