package io.dronekit.request

import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.pattern.after
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.util.ByteString
import io.dronekit.oauth._
import org.joda._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.immutable.SortedMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

class RequestException(msg: String) extends RuntimeException(msg)

class Request(baseUri: String, client: Option[ESHttpClient] = None) {
  require(baseUri.startsWith("http"))
  val uri = java.net.URI.create(baseUri)
  var httpTimeout = 60.seconds
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val adapter: LoggingAdapter = Logging(system, "AkkaRequest")

  val dateTimeFormatter = time.format.ISODateTimeFormat.dateTime()

  private val hostname = uri.getHost()
  private val httpScheme = if (uri.getScheme == null) "https" else uri.getScheme
  private val port = if (uri.getPort == -1) (if (httpScheme == "http") 80 else 443) else uri.getPort
  private val _outgoingConn = if (httpScheme == "https") {
    Http().outgoingConnectionTls(hostname, port)
  } else {
    Http().outgoingConnection(hostname, port)
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
  sealed case class RequestLog(requestId: String, url: String, method: String, data: String, timestamp: time.DateTime) extends LogMessage
  sealed case class ResponseLog(requestId: String, status: String, data: String, timestamp: time.DateTime, latency: Long) extends LogMessage

  def indexRequest(req: RequestLog, metrics: Map[String, String]): Unit = {
    if (client.isDefined) {
      val data = Map(
        "timestamp" -> req.timestamp.toString(dateTimeFormatter),
        "requestId" -> req.requestId,
        "url" -> req.url,
        "method" -> req.method,
        "data" -> req.data
      ) ++ metrics
      val indexFuture = client.get.indexDocument("akka-requests", "request", None, data)
      indexFuture.onComplete {
        case Success(httpResponse) =>
          if (httpResponse.status.intValue() >= 300) adapter.error(s"indexRequest failed: $httpResponse")
        case Failure(ex) => adapter.error(ex, "Failed to save request to Elasticsearch")
      }
    }
    adapter.debug(s"${req.requestId}:${req.method}:${req.url}:${req.data}")
  }

  def indexResponse(resp: ResponseLog, metrics: Map[String, String]): Unit = {
    if (client.isDefined) {
      val data = Map[String, String](
        "timestamp" -> resp.timestamp.toString(dateTimeFormatter),
        "requestId" -> resp.requestId,
        "status" -> resp.status,
        "latency" -> resp.latency.toString,
        "data" -> resp.data
      ) ++ metrics
      val indexFuture = client.get.indexDocument("akka-requests", "response", None, data)
      indexFuture.onComplete {
        case Success(httpResponse) =>
          if (httpResponse.status.intValue() >= 300) adapter.error(s"indexResponse failed: $httpResponse")
        case Failure(ex) => adapter.error(ex, "Failed to save response to Elasticsearch")
      }
    }
    adapter.debug(s"Response:${resp.requestId}:${resp.status}:${resp.timestamp}\n${resp.data}")
  }

  def indexTimeout(requestId: String, metrics: Map[String, String]): Unit = {
    if (client.isDefined) {
      val indexFuture = client.get.indexDocument(
        "akka-requests", "timeout", None, Map("requestId" -> requestId) ++ metrics)
      indexFuture.onComplete {
        case Success(httpResponse) =>
          if (httpResponse.status.intValue() >= 300) adapter.error(s"indexResponse failed: $httpResponse")
        case Failure(ex) => adapter.error(ex, "Failed to save response to Elasticsearch")
      }
    }
    adapter.debug(s"Timeout:$requestId")
  }

  def entityFlow(message: LogMessage, metrics: Map[String, String]): Flow[ByteString, ByteString, Any] = {
    Flow() {implicit b =>
      import FlowGraph.Implicits._

      val indexSink = message match {
       case req: RequestLog => {
         b.add(Sink.foreach[ByteString]{bs =>
         indexRequest(req.copy(data = bs.utf8String), metrics)
         })
        }
        case resp: ResponseLog => {
          b.add(Sink.foreach[ByteString]{bs =>
          indexResponse(resp.copy(data = bs.utf8String), metrics)
          })
        }
      }

      val entityBroadcast = b.add(Broadcast[ByteString](2))
      entityBroadcast.out(0) ~> indexSink
      (entityBroadcast.in, entityBroadcast.out(1))
    }
  }


  def requestFlow(request: HttpRequest, requestId: String, metrics: Map[String, String]): Future[HttpResponse] = {
    val requestLog = RequestLog(requestId = requestId.toString, url = request.getUri().toString,
      method = request.method.name, data = "", timestamp = new time.DateTime())
    val newRequest = if (request.entity.isKnownEmpty()) {

      indexRequest(requestLog, metrics)
    request
    }
    else
      request.copy(entity = request.entity.transformDataBytes(entityFlow(requestLog, metrics)))
    val resp = Source.single(newRequest).via(_outgoingConn)
      .map{response =>
        val now = new time.DateTime()
        val latency = new time.Duration(requestLog.timestamp, now).getMillis
        val responseLog = ResponseLog(requestId = requestId, status = response.status.intValue().toString, data = "", timestamp = now, latency = latency)
        response.copy(entity = response.entity.transformDataBytes(entityFlow(responseLog, metrics)))}
      .runWith(Sink.head)

    Future.firstCompletedOf(
      List(resp,
        after(httpTimeout, system.scheduler)(Future {indexTimeout(requestId, metrics); throw new TimeoutException})))
  }

  def get(uri: String, params: Map[String, String] = Map(), method: HttpMethod=HttpMethods.GET,
          oauth: Oauth=new Oauth("", ""), metrics: Map[String, String] = Map()): Future[HttpResponse] = {
    var headers = List(RawHeader("Accept", "*/*"))

    if (oauth.canSignRequests) {
      // get a signed header
      headers = List(
        RawHeader(
          "Authorization",
          oauth.getSignedHeader(baseUri+uri, "GET", params)
        ),
        RawHeader("Accept", "*/*")
      )
    }

    var queryParams = ""
    if (params.nonEmpty) {
      queryParams = "?" + getFormURLEncoded(params)
    }

    val requestID = java.util.UUID.randomUUID()
    val request = HttpRequest(uri = uri + queryParams, method=method, headers=headers)
    requestFlow(request, requestID.toString, metrics)
  }

  def post(uri: String, params: Map[String, String] = Map(), oauth: Oauth=new Oauth("", ""), json: Boolean = true,
           metrics: Map[String, String] = Map()): Future[HttpResponse] = {

    var headers = List(RawHeader("Accept", "*/*"))

    if (oauth.hasKeys && !oauth.canSignRequests) {
      if (oauth.authProgress == AuthProgress.Unauthenticated) {
        headers = List(RawHeader("Authorization", oauth.getRequestTokenHeader(baseUri+uri)), RawHeader("Accept", "*/*"))
      } else if (oauth.authProgress == AuthProgress.HasRequestTokens) {
        headers = List(RawHeader("Authorization", oauth.getAccessTokenHeader(baseUri+uri)), RawHeader("Accept", "*/*"))
      }
    } else if (oauth.canSignRequests) {
      headers = List(RawHeader("Authorization", oauth.getSignedHeader(baseUri+uri, "POST", params)), RawHeader("Accept", "*/*"))
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
    requestFlow(postRequest, requestID.toString, metrics)
  }

  def delete(uri: String): Future[HttpResponse] =
    get(uri=uri, method=HttpMethods.DELETE)
}
