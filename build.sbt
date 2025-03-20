name := "pekko-request"
organization := "com.arcgis.sitescan"
licenses += "ISC" -> url("https://opensource.org/licenses/ISC")
version := "5.0.1"

scalaVersion := "2.13.16"
scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

libraryDependencies ++= {
  val pekkoV = "1.1.3"
  val pekkoHttpV = "1.1.0"
  
  Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoV,
    "org.apache.pekko" %% "pekko-stream" % pekkoV,
    
    "org.apache.pekko" %% "pekko-http-core" % pekkoHttpV,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpV,

    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4",
    
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpV % Test,
    "com.github.pjfanning" %% "pekko-http-play-json" % "3.1.0" % Test,
    "org.playframework" %% "play-json" % "3.0.4" % Test,
    "org.scalatest" %% "scalatest" % "3.2.19" % Test,
    "ch.qos.logback" % "logback-classic" % "1.3.11" % Test
  )
}
