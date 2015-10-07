package io.dronekit.request

import java.util.UUID
import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.pattern.after
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.stream.stage.{Context, PushPullStage, SyncDirective}
import akka.util.ByteString
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.ElasticDsl._
import io.dronekit.oauth._
import org.elasticsearch.common.joda.time
import org.elasticsearch.common.joda.time.format.ISODateTimeFormat
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.immutable.SortedMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

class RequestException(msg: String) extends RuntimeException(msg)

class Request(baseUri: String, isHttps: Boolean = false, client: Option[ElasticClient] = None) {
  var httpTimeout = 60.seconds
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val adapter: LoggingAdapter = Logging(system, "AkkaRequest")

  val dateTimeFormatter = ISODateTimeFormat.dateTime()

  // parse out the port number if there is a colon
  val splitUri = baseUri.split(":")
  if (splitUri.size > 2) {
    throw new RequestException("BaseURI has too many colons, not sure how to parse out port. Do not include protocol in the base URI.")
  }

  private val _outgoingConn = if (isHttps) {
    Http().outgoingConnectionTls(if (splitUri.size == 2) splitUri(0) else baseUri, if (splitUri.size == 2) splitUri(1).toInt else 443 )
  } else {
    Http().outgoingConnection(if (splitUri.size == 2) splitUri(0) else baseUri, if (splitUri.size == 2) splitUri(1).toInt else 80 )
  }

  private val _netLoc = if(isHttps) {
    "https://"
  } else {
    "http://"
  }

  def byteStringToString(data: ByteString): String = data.map(_.toChar).mkString

  private def getFormURLEncoded(params: Map[String, String]): String = {
    val sortedParams = SortedMap(params.toList:_*)
    sortedParams.map { paramTuple =>
      java.net.URLEncoder.encode(paramTuple._1, "UTF-8") + "=" + java.net.URLEncoder.encode(paramTuple._2, "UTF-8")
    }.mkString("&")
  }

  def retry(retries: Int = 3)(req:() => Future[HttpResponse]): Future[HttpResponse] = {
    val p = Promise[HttpResponse]()

    def retryHelper(retryNum: Int = retries): Unit = {
      // catch timeout errors
      req().onComplete{
        case Success(v) => p.success(v)
        case Failure(ex) => ex match {
          case ex: TimeoutException => {
            if (retryNum <= 0) {
              p.failure(ex)
            } else {
              retryHelper(retryNum - 1)
            }
          }
          case _ => p.failure(ex)
        }
      }
    }

    retryHelper()
    p.future
  }

  sealed trait LogMessage
  sealed case class RequestLog(requestId: String, url: String, method: String, data: String, timestamp: String) extends LogMessage
  sealed case class ResponseLog(requestId: String, status: String, data: String, timestamp: String) extends LogMessage

  def indexRequest(req: RequestLog): Unit = {
    if (client.isDefined)
      client.get.execute {
        index into "akka-requests" / "request" fields (
          "timestamp" -> req.timestamp,
          "requestId" -> req.requestId,
          "url" -> req.url,
          "method" -> req.method,
          "data" -> req.data
          )
      }

    adapter.debug(s"${req.requestId}:${req.method}:${req.url}:${req.data}")
  }

  def indexResponse(resp: ResponseLog): Unit = {
    if (client.isDefined)
      client.get.execute{
        index into "akka-requests" / "response" fields (
          "timestamp" -> resp.timestamp,
          "requestId" -> resp.requestId,
          "status" -> resp.status,
          "data" -> resp.data
          )
      }

    adapter.debug(s"Response:${resp.requestId}:${resp.status}:${resp.timestamp}\n${resp.data}")
  }

  def entityFlow(message: LogMessage): Flow[ByteString, ByteString, Any] = {
    Flow() {implicit b =>
      import FlowGraph.Implicits._

      val indexSink = message match {
       case req: RequestLog => {
         b.add(Sink.foreach[ByteString]{bs =>
         indexRequest(req.copy(data = bs.utf8String))
         })
        }
        case resp: ResponseLog => {
          b.add(Sink.foreach[ByteString]{bs =>
          indexResponse(resp.copy(data = bs.utf8String))
          })
        }
      }

      val entityBroadcast = b.add(Broadcast[ByteString](2))
      entityBroadcast.out(0) ~> indexSink
      (entityBroadcast.in, entityBroadcast.out(1))
    }
  }

  def requestFlow(requestID: UUID): Flow[HttpRequest, HttpResponse, Any] = {
    Flow() {implicit builder: FlowGraph.Builder[Unit] =>

      val logResponseFlow = Flow[HttpResponse].map{resp =>
        val logMessage = ResponseLog(requestId = requestID.toString, status = resp.status.intValue().toString, data = "",
          timestamp = new time.DateTime().toString(dateTimeFormatter))
        resp.copy(entity = resp.entity.transformDataBytes(entityFlow(logMessage)))
      }

      val toResponseFlow = builder.add(Flow[HttpRequest].map{req =>
        val logMessage = RequestLog(requestId = requestID.toString, url = req.getUri().toString,
          method = req.method.name, data = "", timestamp = new time.DateTime().toString(dateTimeFormatter))

        if (req.entity.isKnownEmpty()) {
          indexRequest(logMessage)
          req
        }
        else {
          req.copy(entity = req.entity.transformDataBytes(entityFlow(logMessage)))
        }
        }.via(_outgoingConn).via(logResponseFlow)
      )

      (toResponseFlow.inlet, toResponseFlow.outlet)
    }
  }


  def get(uri: String, params: Map[String, String] = Map(), method: HttpMethod=HttpMethods.GET,
          oauth: Oauth=new Oauth("", "")): Future[HttpResponse] = {
    var headers = List(RawHeader("Accept", "*/*"))

    if (oauth.canSignRequests) {
      // get a signed header
      headers = List(
        RawHeader(
          "Authorization",
          oauth.getSignedHeader(_netLoc+baseUri+uri, "GET", params)
        ),
        RawHeader("Accept", "*/*")
      )
    }

    var queryParams = ""
    if (params.nonEmpty) {
      queryParams = "?" + getFormURLEncoded(params)
    }

    val requestID = java.util.UUID.randomUUID()
    val resp = Source.single(HttpRequest(uri = uri + queryParams, method=method, headers=headers))
      .via(requestFlow(requestID))
      .runWith(Sink.head)

    Future.firstCompletedOf(List(resp, after(httpTimeout, system.scheduler)(Future.failed(new TimeoutException))))
  }

  def post(uri: String, params: Map[String, String]=Map(), oauth: Oauth=new Oauth("", ""), json: Boolean=true): Future[HttpResponse] = {

    var headers = List(RawHeader("Accept", "*/*"))

    if (oauth.hasKeys && !oauth.canSignRequests) {
      if (oauth.authProgress == AuthProgress.Unauthenticated) {
        headers = List(RawHeader("Authorization", oauth.getRequestTokenHeader(_netLoc+baseUri+uri)), RawHeader("Accept", "*/*"))
      } else if (oauth.authProgress == AuthProgress.HasRequestTokens) {
        headers = List(RawHeader("Authorization", oauth.getAccessTokenHeader(_netLoc+baseUri+uri)), RawHeader("Accept", "*/*"))
      }
    } else if (oauth.canSignRequests) {
      headers = List(RawHeader("Authorization", oauth.getSignedHeader(_netLoc+baseUri+uri, "POST", params)), RawHeader("Accept", "*/*"))
    }

    val entity = if (params.nonEmpty && !oauth.canSignRequests && json) {
        val paramStr = ByteString(params.toJson.toString())
        HttpEntity(contentType=ContentTypes.`application/json`, contentLength=paramStr.length, Source(List(paramStr)))
    } else if ((params.nonEmpty && json == false) || oauth.canSignRequests) {

      // application/x-www-form-urlencoded
      val paramStr = ByteString(getFormURLEncoded(params))
      HttpEntity(
        contentType = ContentType(MediaTypes.`application/x-www-form-urlencoded`),
        contentLength = paramStr.length,
        Source(List(paramStr)))
    } else {
      HttpEntity.Empty
    }

    val postRequest = HttpRequest(
      uri = uri,
      method = HttpMethods.POST,
      headers = headers,
      entity = entity)

    val requestID = java.util.UUID.randomUUID()
    val resp = Source.single(postRequest).via(requestFlow(requestID)).runWith(Sink.head)

    Future.firstCompletedOf(List(resp, after(httpTimeout, system.scheduler)(Future.failed(new TimeoutException))))
  }

  def delete(uri: String): Future[HttpResponse] =
    get(uri=uri, method=HttpMethods.DELETE)
}
