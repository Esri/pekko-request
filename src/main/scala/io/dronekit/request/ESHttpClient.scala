package io.dronekit.request

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import spray.json._

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by jasonmartens on 10/8/15.
 *
 */
class ESHttpClient(endpoint: String)(implicit system: ActorSystem, materializer: ActorMaterializer) {
  require(endpoint.startsWith("http"))
  implicit val ec: ExecutionContext = system.dispatcher

  // For json serialization
  implicit object MapJsonFormat extends JsonFormat[Map[String, Any]] {
    override def read(json: JsValue): Map[String, Any] =
      throw new DeserializationException("Unmarshalling maps is not supported")

    override def write(obj: Map[String, Any]): JsValue = {
      val x = obj.map {
        case (key: String, value: String) => key -> JsString(value)
        case (key: String, value: Int) => key -> JsNumber(value)
        case (key: String, value: Long) => key -> JsNumber(value)
        case (key: String, value: Float) => key -> JsNumber(value)
        case (key: String, value: Double) => key -> JsNumber(value)
        case (key: String, value: Any) => key -> JsString(value.toString)
      }
      JsObject(x)
    }
  }

  val uri = java.net.URI.create(endpoint)
  private val outgoingConn = if (uri.getScheme() == "https") {
    Http().outgoingConnectionTls(uri.getHost, if (uri.getPort == -1) 443 else uri.getPort)
  } else {
    Http().outgoingConnection(uri.getHost, if (uri.getPort == -1) 80 else uri.getPort)
  }

  def indexDocument(index: String, docType: String, docId: Option[String] = None, data: Map[String, String]): Future[HttpResponse] = {
    val documentKey = if (docId.isDefined) s"/${docId.get}" else ""
    val uri = s"/$index/$docType$documentKey"
    post(uri, data)
  }

  private def post(uri: String, data: Map[String, String]): Future[HttpResponse] = {
    import DefaultJsonProtocol._
    // Elasticsearch takes json-formatted parameters with x-www-form-urlencoded which is brain-dead
    val paramStr = data.toJson.compactPrint
    val entity = HttpEntity(contentType = MediaTypes.`application/x-www-form-urlencoded`, paramStr)
    val request = HttpRequest(method = HttpMethods.POST, uri = uri, entity = entity)
    Source.single(request).via(outgoingConn).runWith(Sink.head)
  }
}
