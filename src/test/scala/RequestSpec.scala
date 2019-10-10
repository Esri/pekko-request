import java.util.concurrent.TimeoutException

import akka.stream.ActorMaterializer
import akka.util.Timeout
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
    val client = Client("https://httpbin.org", PrintLogger)
    it("should be able to GET", PostTest) {
      ScalaFutures.whenReady(client.get[JsObject]("/get"), timeout(5 seconds), interval(500 millis)) { jsObj =>
       assert(jsObj.fields("url").toString == """"https://httpbin.org/get"""")
       assert(jsObj.fields("args").toString == """{}""")
      }
    }

    it("should be able to POST") {
      ScalaFutures.whenReady(client.postUrlEncoded[JsObject]("/post", Map("item"->"1", "item"->"2")), timeout(5 seconds), interval(500 millis)) { jsObj =>
        assert(jsObj.fields("url").toString === """"https://httpbin.org/post"""")
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
}
