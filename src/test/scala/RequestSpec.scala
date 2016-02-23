import java.util.concurrent.TimeoutException

import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.Timeout
import io.dronekit.oauth._
import io.dronekit.request.Request
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Tag, _}
import spray.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.language.postfixOps
import scala.util.{Failure, Success}

object PostTest extends Tag("PostTest")

case class TestObj(name: String, age: Int)

object TestProtocol extends DefaultJsonProtocol {
  implicit val testObjFormat = jsonFormat2(TestObj.apply)
}

class RequestSpec extends FunSpec with Matchers with ScalaFutures {
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  import testSystem.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(5 seconds)
  import TestProtocol._

  def getResData(res: HttpResponse): Future[String] = {
    val p = Promise[String]()
    val data = res.entity.dataBytes.runWith(Sink.head)
    data.onComplete {
      case Success(byteSeq) =>
        p.success(byteSeq.map(b => b.toChar).mkString)
      case Failure(ex) => ex match {
        case ex: java.util.NoSuchElementException => p.success("")
        case _ => p.failure(ex)
      }
    }
    p.future
  }

  describe("Requests") {
    val request = new Request("http://httpbin.org", client = None)
    it("should be able to GET", PostTest) {
      ScalaFutures.whenReady(request.get("/get"), timeout(5 seconds), interval(500 millis)) { res =>
        ScalaFutures.whenReady(getResData(res), timeout(5 seconds), interval(500 millis)) { data =>
           val jsObj = data.parseJson.asJsObject
           assert(jsObj.fields("headers").toString == """{"Accept":"*/*","Host":"httpbin.org","User-Agent":"akka-http/2.4.1"}""")
           assert(jsObj.fields("url").toString == """"http://httpbin.org/get"""")
           assert(jsObj.fields("args").toString == """{}""")
        }
      }
    }

    it("should be able to POST") {
      ScalaFutures.whenReady(request.post("/post", Map("item"->"1", "item"->"2")), timeout(5 seconds), interval(500 millis)) { res =>
        ScalaFutures.whenReady(getResData(res), timeout(5 seconds), interval(500 millis)) { data =>
          val jsObj = data.parseJson.asJsObject
          // make sure that the url, args, and headers are correct
          val controlHeader = """
                                |{"Content-Length":"12","Accept":"*/*",
                                |"Content-Type":"application/json",
                                |"User-Agent":"akka-http/2.4.1",
                                |"Host":"httpbin.org"}""".stripMargin.replace("\n", "")
          assert(jsObj.fields("headers").toString === controlHeader)
          assert(jsObj.fields("url").toString === """"http://httpbin.org/post"""")
          assert(jsObj.fields("args").toString === """{}""")
          assert(jsObj.fields("json").toString === """{"item":"2"}""")
        }
      }
    }

   it("should be able to POST a json body") {
     val testObj = TestObj("Marqod",222)
     ScalaFutures.whenReady(request.post("/post", json=true, body=Some(testObj.toJson.toString)), timeout(5 seconds), interval(500 millis)) { res =>
       ScalaFutures.whenReady(getResData(res), timeout(5 seconds), interval(500 millis)) { data =>
        val jsObj = data.parseJson.asJsObject
        val tObj = jsObj.fields("json").asJsObject.convertTo[TestObj]
        assert(tObj.name == testObj.name && tObj.age == testObj.age)
       }
     }
   }

    it("should be able to retry on timeout failures") {
      var count = 0

      request.httpTimeout = 1 millisecond // set super short timeout
      val req = request.retry(3)(()=>{
          count = count + 1
          println(s"Retrying request on attempt $count")
          request.get("/get")})
      intercept[TimeoutException] {
        Await.result(req, 20 seconds)
      }
      count shouldBe 4
    }
  }

   describe("Oauth") {
     val baseUri = "http://oauthbin.com"
     val request = new Request(baseUri)

     describe("when it has a key and secret") {
       val oauth = new Oauth(key="key", secret="secret")
       it ("should be able to get a request token"){
         ScalaFutures.whenReady(request.post("/v1/request-token", oauth=oauth), timeout(5 seconds), interval(500 millis)) { res =>
           ScalaFutures.whenReady(getResData(res), timeout(5 seconds), interval(500 millis)) { data =>
             assert(data === "oauth_token=requestkey&oauth_token_secret=requestsecret")
           }
         }
       }
     }

     describe("when it has a request secret") {
       val oauth = new Oauth(key="key", secret="secret")
       oauth.setRequestTokens("requestkey", "requestsecret")
       it("should be able to get an access token"){
         ScalaFutures.whenReady(request.post("/v1/access-token", oauth=oauth), timeout(5 seconds), interval(500 millis)) { res =>
           ScalaFutures.whenReady(getResData(res), timeout(5 seconds), interval(500 millis)) { data =>
             assert(data==="oauth_token=accesskey&oauth_token_secret=accesssecret")
           }
         }
       }
     }

     describe("when it has an access token") {
       val oauth = new Oauth(key="key", secret="secret")
       oauth.setAccessTokens("accesskey", "accesssecret")

       it("should be able to send a GET") {
         ScalaFutures.whenReady(request.get("/v1/echo", oauth=oauth, params=Map("a"->"1", "b"->"2")), timeout(5 seconds), interval(500 millis)) { res =>
           ScalaFutures.whenReady(getResData(res), timeout(5 seconds), interval(500 millis)) { data =>
             assert(data==="a=1&b=2")
           }
         }
       }

       it("should be able to send a POST") {
         ScalaFutures.whenReady(request.post("/v1/echo", oauth=oauth, params=Map("c"->"1", "d"->"2")), timeout(5 seconds), interval(500 millis)) { res =>
           ScalaFutures.whenReady(getResData(res), timeout(5 seconds), interval(500 millis)) { data =>
             assert(data==="c=1&d=2")
           }
         }
       }
     }
   }
}
