import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import io.dronekit.oauth._
import io.dronekit.request.Request
import org.scalatest.{Matchers, FunSpec}
import org.scalatest.concurrent.ScalaFutures
import spray.json._

import scala.concurrent.duration._

class RequestSpec extends FunSpec with Matchers with ScalaFutures{
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  import testSystem.dispatcher
  implicit val materializer = ActorMaterializer()

  def getResData(res: HttpResponse, cb:(String)  =>  Unit) = {
    val data = res.entity.dataBytes.runWith(Sink.head)
    data.map(byteSeq => byteSeq.map(b => b.toChar)).map(charSeq => {
      cb(charSeq.mkString)
    })
  }

  describe("Requests") {
    val request = new Request("httpbin.org")
    whenReady(request.get("/get"), timeout(5 seconds), interval(500 millis)) { res =>
      getResData(res, {(data: String) => {
        val jsObj = data.parseJson.asJsObject
        // make sure that the url, args, and headers are correct
        it("should be able to GET") {
          assert(jsObj.fields("headers").toString == """{"Accept":"*/*","Host":"httpbin.org","User-Agent":"akka-http/2.3.12"}""")
          assert(jsObj.fields("url").toString == """"http://httpbin.org/get"""")
          assert(jsObj.fields("args").toString == """{}""")
        }
      }})
    }

    whenReady(request.post("/post", Map("item"->"1", "item"->"2")), timeout(5 seconds), interval(500 millis)) { res =>
      getResData(res, {(data: String) => {
        val jsObj = data.parseJson.asJsObject
        // make sure that the url, args, and headers are correct
        it("should be able to POST") {
          val controlHeader = """
              |{"Content-Length":"12","Accept":"*/*",
              |"Content-Type":"application/json",
              |"User-Agent":"akka-http/2.3.12",
              |"Host":"httpbin.org"}""".stripMargin.replace("\n", "")
          assert(jsObj.fields("headers").toString === controlHeader)
          assert(jsObj.fields("url").toString === """"http://httpbin.org/post"""")
          assert(jsObj.fields("args").toString === """{}""")
          assert(jsObj.fields("json").toString === """{"item":"2"}""")
        }
      }})
    }
  }

  describe("Oauth") {
    val baseUri = "oauthbin.com"
    val request = new Request(baseUri)

    describe("when it has a key and secret") {
      val oauth = new Oauth(key="key", secret="secret")
      whenReady(request.post("/v1/request-token", oauth=oauth), timeout(5 seconds), interval(500 millis)) { res =>
        getResData(res, {(data: String) => {
          it ("should be able to get a request token"){
            assert(data === "oauth_token=requestkey&oauth_token_secret=requestsecret")
          }
        }})
      }
    }

    describe("when it has a request secret") {
      val oauth = new Oauth(key="key", secret="secret")
      oauth.setRequestTokens("requestkey", "requestsecret")

      whenReady(request.post("/v1/access-token", oauth=oauth), timeout(5 seconds), interval(500 millis)) { res =>
        getResData(res, {(data: String) => {
          it("should be able to get an access token"){
            assert(data==="oauth_token=accesskey&oauth_token_secret=accesssecret")
          }
        }})
      }
    }

    describe("when it has an access token") {
      val oauth = new Oauth(key="key", secret="secret")
      oauth.setAccessTokens("accesskey", "accesssecret")

      whenReady(request.get("/v1/echo", oauth=oauth, params=Map("a"->"1", "b"->"2")), timeout(5 seconds), interval(500 millis)) { res =>
        getResData(res, {(data: String) => {
          it("should be able to send a GET") {
            assert(data==="a=1&b=2")
          }
        }})
      }

      whenReady(request.post("/v1/echo", oauth=oauth, params=Map("c"->"1", "d"->"2")), timeout(5 seconds), interval(500 millis)) { res =>
        getResData(res, {(data: String) => {
          it("should be able to send a POST") {
            assert(data==="c=1&d=2")
          }
        }})
      }
    }
  }
}
