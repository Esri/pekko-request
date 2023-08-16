name := "pekko-request"
organization := "io.dronekit"
licenses += "ISC" -> url("https://opensource.org/licenses/ISC")
version := "5.0.0"

scalaVersion := "2.13.11"
scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  val pekkoV = "1.0.1"
  val pekkoHttpV = "1.0.0"
  
  Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoV,
    "org.apache.pekko" %% "pekko-stream" % pekkoV,
    
    "org.apache.pekko" %% "pekko-http-core" % pekkoHttpV,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpV,

    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
    
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpV % Test,
    "com.github.pjfanning" %% "pekko-http-play-json" % "2.1.0",
    "com.typesafe.play" %% "play-json" % "2.8.2" % Test,
    "org.scalatest" %% "scalatest" % "3.2.16" % Test,
    "ch.qos.logback" % "logback-classic" % "1.3.11" % Test
  )
}
