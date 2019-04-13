enablePlugins(JavaAppPackaging)

name := "akka-request"
organization := "io.dronekit"
version := "3.2.0"
scalaVersion := "2.12.1"
crossScalaVersions := Seq("2.11.8", "2.12.1")

resolvers += "Artifactory" at "https://dronekit.artifactoryonline.com/dronekit/libs-snapshot-local/"
scalacOptions := Seq("-Ywarn-unused-import")

credentials += Credentials(Path.userHome / ".sbt" / ".credentials")
isSnapshot := true
publishTo := {
  val artifactory = "https://dronekit.artifactoryonline.com/"
  if (isSnapshot.value)
    Some("snapshots" at artifactory + s"dronekit/libs-snapshot-local;build.timestamp=${new java.util.Date().getTime}")
  else
    Some("snapshots" at artifactory + "dronekit/libs-release-local")
}

libraryDependencies ++= {
  val akkaV = "2.5.0"
  val akkaHttpV = "10.0.5"
  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    
    "com.typesafe.akka" %% "akka-http-core" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV,
    
    "io.spray" %%  "spray-json" % "1.3.3",
    "commons-codec" % "commons-codec" % "1.6",
    "cloud.drdrdr" %% "oauth-headers" % "0.3",
    "org.scalatest" %% "scalatest" % "3.0.1" % "test",
    "ch.qos.logback" % "logback-classic" % "1.1.3",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
    "joda-time" % "joda-time" % "2.8.2"
  )
}
