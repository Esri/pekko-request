// © 2019 3D Robotics. License: ISC
import java.util.concurrent.TimeoutException

import akka.stream.ActorMaterializer
import akka.util.Timeout
import io.dronekit.request.{Client, PrintLogger}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest._
import play.api.libs.json._
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

case class TestObj(name: String, age: Int)

object TestObj {
  implicit val format = Json.format[TestObj]
}

class RequestSpec extends FunSpec with Matchers with ScalaFutures {
  implicit val testSystem = akka.actor.ActorSystem("test-system")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(5 seconds)

  describe("Requests") {
    val client = Client("https://httpbin.org", PrintLogger)
    it("should be able to GET") {
      ScalaFutures.whenReady(client.get[JsObject]("/get"), timeout(5 seconds), interval(500 millis)) { jsObj =>
       assert((jsObj \ "url").as[String] === """https://httpbin.org/get""")
      }
    }

    it("should be able to POST") {
      ScalaFutures.whenReady(client.postUrlEncoded[JsObject]("/post", Map("item"->"1", "item"->"2")), timeout(5 seconds), interval(500 millis)) { jsObj =>
        assert((jsObj \ "url").as[String] === """https://httpbin.org/post""")
        assert((jsObj \ "form").as[Map[String, String]] === Map("item"->"1", "item"->"2"))
      }
    }

    it("should be able to POST a json body") {
      val testObj = TestObj("Marqod",222)
      ScalaFutures.whenReady(client.post[TestObj, JsObject]("/post", testObj), timeout(5 seconds), interval(500 millis)) { jsObj =>
        val tObj = (jsObj \"json").as[TestObj]
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
