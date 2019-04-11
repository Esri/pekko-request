enablePlugins(JavaAppPackaging)

name := "akka-request"
organization := "io.dronekit"
version := "3.3.0"
scalaVersion := "2.12.8"

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
  val akkaV = "2.5.22"
  val akkaHttpV = "10.1.8"
  
  Seq(
    "com.typesafe.akka" %% "akka-actor" % akkaV,
    "com.typesafe.akka" %% "akka-stream" % akkaV,
    
    "com.typesafe.akka" %% "akka-http-core" % akkaHttpV,
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,

    "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
    "cloud.drdrdr" %% "oauth-headers" % "0.3",
    
    "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpV % Test,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV % Test,
    "io.spray" %%  "spray-json" % "1.3.3" % Test,
    "org.scalatest" %% "scalatest" % "3.0.1" % Test,
    "ch.qos.logback" % "logback-classic" % "1.1.3" % Test
  )
}
