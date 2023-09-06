# pekko-request
A http/https request lib for pekko-http.

## Usage

```scala
import com.arcgis.sitescan.request.Client

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
