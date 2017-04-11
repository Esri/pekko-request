#akka-request
A http/https request lib for akka-http.

Also works with Oauth 1.0a.

##Usage

```scala
import io.dronekit.request.Client

val client = Client("http://httpbin.org")
client.get[String]("/get")
client.post[String, String]("/post", "body")

// make it auto retry on timeout
Client.retry(3){ client.get[String]("/get")} )
```

If you want to use Oauth

```scala
import io.dronekit.request.Client
import io.dronekit.oauth._

val oauth = new Oauth("key", "secret")
val client = Client("http://oauthbin.com").withOauth(oauth)

client.post[String, String]("/v1/requesttoken", "")
oauth.setRequestToken("requestkey", "requestsecret")

client.post[String, String]("/v1/accesstoken", "")
oauth.setAccessToken("accesskey", "accesssecret")

// send off signed requests
client.get[String]("/v1/echo", params=Map("get"->"value"))
client.postUrlEncoded[String]("/v1/echo", params=Map("post"->"value"))
```

##Installation

1. Clone down this repo
2. `sbt publish-local`
3. add the following to `build.sbt`

```
libraryDependencies += "io.dronekit" %% "akka-request" % "3.0.0"
```

##Testing
run `sbt test`

##License
????
