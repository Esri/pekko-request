#akka-request
A http/https request lib for akka-http.

Also works with Oauth 1.0a.

##Usage

```scala
import io.dronekit.request

val request = new Request("httpbin.org", isHttps=false)
request.get("/get")
request.get("/post", params=Map("herp"->"derp"))
```

If you want to use Oauth

```scala
import io.dronekit.request
import io.dronekit.oauth

val request = new Request("oauthbin.com", isHttps=false)
val oauth = new Oauth("key", "secret")

request.post("/v1/requesttoken", oauth=oauth)
oauth.setRequestToken("requestkey", "requestsecret")

request.post("/v1/accesstoken", oauth=oauth)
oauth.setAccessToken("accesskey", "accesssecret")

// send off signed requests
request.get("/v1/echo", params=Map("get"->"value"), oauth=oauth)
request.post("/v1/echo", params=Map("post"->"value"), oauth=oauth)
```

##Installation

1. Clone down this repo
2. `sbt publish-local`
3. add the following to `build.sbt`

```
libraryDependencies += "io.dronekit" %% "akka-request" % "0.1"
```

##Testing
run `sbt test`

##License
????
