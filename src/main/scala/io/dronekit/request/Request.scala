package io.dronekit.request

import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.stream.stage.{SyncDirective, Context, PushPullStage}
import akka.util.{Timeout, ByteString}
import io.dronekit.oauth._
import spray.json.DefaultJsonProtocol._
import spray.json._
import akka.pattern.after
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.collection.immutable.SortedMap
import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}

class RequestException(msg: String) extends RuntimeException(msg)

class LogByteStream()(implicit adapter: LoggingAdapter) extends PushPullStage[ByteString, ByteString] {
  override def onPush(elem: ByteString, ctx: Context[ByteString]): SyncDirective = {
    adapter.debug(s"Payload: ${elem.map(_.toChar).mkString}")
    ctx.push(elem)
  }

  override def onPull(ctx: Context[ByteString]): SyncDirective =
    ctx.pull()
}


class Request(baseUri: String, isHttps: Boolean = false) {
  var httpTimeout = 60.seconds
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val adapter: LoggingAdapter = Logging(system, "AkkaRequest")

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


  def logRequest(request: HttpRequest): HttpRequest = {
    implicit val adapter: LoggingAdapter = Logging(system, "AkkaRequest:REQUEST")
    adapter.debug(s"${request.getUri()}, ${request.method}")
    if (request.entity.isKnownEmpty()) request
    else request.copy(entity = request.entity.transformDataBytes(
      Flow[ByteString].transform(() => new LogByteStream()(adapter))))
  }

  def logResponse(response: HttpResponse): HttpResponse = {
    implicit val adapter: LoggingAdapter = Logging(system, "AkkaRequest:RESPONSE")
    adapter.debug(s"Http Response Code ${response.status}")
    response.copy(entity = response.entity.transformDataBytes(
      Flow[ByteString].transform(() => new LogByteStream()(adapter))))
  }

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

    val resp = Source.single(HttpRequest(uri = uri + queryParams, method=method, headers=headers))
      .map(logRequest)
      .via(_outgoingConn)
      .map(logResponse)
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

    val resp = Source.single(postRequest)
      .map(logRequest)
      .via(_outgoingConn)
      .map(logResponse)
      .runWith(Sink.head)

    Future.firstCompletedOf(List(resp, after(httpTimeout, system.scheduler)(Future.failed(new TimeoutException))))
  }

  def delete(uri: String): Future[HttpResponse] =
    get(uri=uri, method=HttpMethods.DELETE)
}
