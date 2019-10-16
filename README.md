# akka-request
A http/https request lib for akka-http.

## Usage

```scala
import io.dronekit.request.Client

val client = Client("https://httpbin.org")
client.get[String]("/get")
client.post[String, String]("/post", "body")

// make it auto retry on timeout
Client.retry(3){ client.get[String]("/get")} )
```

## Testing
run `sbt test`

## License
ISC
