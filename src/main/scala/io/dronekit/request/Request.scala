package io.dronekit.request

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.stream.stage.{SyncDirective, Context, PushPullStage}
import akka.util.ByteString
import io.dronekit.oauth._
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.immutable.SortedMap
import scala.concurrent.Future

class LogByteStream()(implicit adapter: LoggingAdapter) extends PushPullStage[ByteString, ByteString] {
  override def onPush(elem: ByteString, ctx: Context[ByteString]): SyncDirective = {
    adapter.debug(s"Payload: ${elem.map(_.toChar).mkString}")
    ctx.push(elem)
  }

  override def onPull(ctx: Context[ByteString]): SyncDirective =
    ctx.pull()
}


class Request(baseUri: String, isHttps: Boolean = false) {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val adapter: LoggingAdapter = Logging(system, "AkkaRequest")

  private val _outgoingConn = if (isHttps) {
    Http().outgoingConnectionTls(baseUri)
  } else {
    Http().outgoingConnection(baseUri)
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
    response.copy(entity = response.entity.transformDataBytes(
      Flow[ByteString].transform(() => new LogByteStream()(adapter))))
  }

  private def getFormURLEncoded(params: Map[String, String]): String = {
    val sortedParams = SortedMap(params.toList:_*)
    sortedParams.map { paramTuple =>
      java.net.URLEncoder.encode(paramTuple._1, "UTF-8") + "=" + java.net.URLEncoder.encode(paramTuple._2, "UTF-8")
    }.mkString("&")
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

    Source.single(HttpRequest(uri = uri + queryParams, method=method, headers=headers))
      .map(logRequest)
      .via(_outgoingConn)
      .map(logResponse)
      .runWith(Sink.head)
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

    Source.single(postRequest)
      .map(logRequest)
      .via(_outgoingConn)
      .map(logResponse)
      .runWith(Sink.head)
  }

  def delete(uri: String): Future[HttpResponse] =
    get(uri=uri, method=HttpMethods.DELETE)
}
