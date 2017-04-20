import java.util.concurrent.TimeoutException

import akka.stream.ActorMaterializer
import akka.util.Timeout
import cloud.drdrdr.oauth.Oauth
import io.dronekit.request.{Client, PrintLogger}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Tag, _}
import spray.json._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

object PostTest extends Tag("PostTest")

case class TestObj(name: String, age: Int)

object TestProtocol extends DefaultJsonProtocol {
  implicit val testObjFormat = jsonFormat2(TestObj.apply)
}

class RequestSpec extends FunSpec with Matchers with ScalaFutures {
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(5 seconds)
  import TestProtocol._

  describe("Requests") {
    val client = Client("http://httpbin.org", PrintLogger)
    it("should be able to GET", PostTest) {
      ScalaFutures.whenReady(client.get[JsObject]("/get"), timeout(5 seconds), interval(500 millis)) { jsObj =>
       assert(jsObj.fields("url").toString == """"http://httpbin.org/get"""")
       assert(jsObj.fields("args").toString == """{}""")
      }
    }

    it("should be able to POST") {
      ScalaFutures.whenReady(client.postUrlEncoded[JsObject]("/post", Map("item"->"1", "item"->"2")), timeout(5 seconds), interval(500 millis)) { jsObj =>
        assert(jsObj.fields("url").toString === """"http://httpbin.org/post"""")
        assert(jsObj.fields("args").toString === """{}""")
        assert(jsObj.fields("form").toString === """{"item":"2"}""")
      }
    }

    it("should be able to POST a json body") {
      val testObj = TestObj("Marqod",222)
      ScalaFutures.whenReady(client.post[TestObj, JsObject]("/post", testObj), timeout(5 seconds), interval(500 millis)) { jsObj =>
        val tObj = jsObj.fields("json").asJsObject.convertTo[TestObj]
        assert(tObj.name == testObj.name && tObj.age == testObj.age)
      }
    }

    it("should be able to retry on timeout failures") {
      var count = 0
      val req = Client.retry(3) {
        count = count + 1
        println(s"Retrying request on attempt $count")
        Future.failed(new TimeoutException)
      }
      intercept[TimeoutException] {
        Await.result(req, 20 seconds)
      }
      count shouldBe 4
    }
  }

  describe("Oauth") {
    val baseUri = "http://oauthbin.com"
    val client = Client(baseUri)

    describe("when it has a key and secret") {
      val oauth = new Oauth(key="key", secret="secret")
      it ("should be able to get a request token"){
        ScalaFutures.whenReady(client.withOauth(oauth).post[String, String]("/v1/request-token", body=""), timeout(5 seconds), interval(500 millis)) { data =>
          assert(data === "oauth_token=requestkey&oauth_token_secret=requestsecret")
        }
      }
    }

    describe("when it has a request secret") {
      val oauth = new Oauth(key="key", secret="secret")
      oauth.setRequestTokens("requestkey", "requestsecret")
      it("should be able to get an access token"){
        ScalaFutures.whenReady(client.withOauth(oauth).post[String, String]("/v1/access-token", body=""), timeout(5 seconds), interval(500 millis)) { data =>
          assert(data==="oauth_token=accesskey&oauth_token_secret=accesssecret")
        }
      }
    }

    describe("when it has an access token") {
      val oauth = new Oauth(key="key", secret="secret")
      oauth.setAccessTokens("accesskey", "accesssecret")

      it("should be able to send a GET") {
        ScalaFutures.whenReady(client.withOauth(oauth).get[String]("/v1/echo", params=Map("a"->"1", "b"->"2")), timeout(5 seconds), interval(500 millis)) { data =>
          assert(data==="a=1&b=2")
        }
      }

      it("should be able to send a POST") {
        ScalaFutures.whenReady(client.withOauth(oauth).postUrlEncoded[String]("/v1/echo", Map("c"->"1", "d"->"2")), timeout(5 seconds), interval(500 millis)) { data =>
          assert(data==="c=1&d=2")
         }
      }
    }
  }
}
