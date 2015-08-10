package io.dronekit.request

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import scala.concurrent.Future
import akka.stream.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.Http
import akka.util.ByteString
import spray.json._
import DefaultJsonProtocol._
import io.dronekit.oauth._
import scala.collection.immutable.SortedMap

//
// class Response(res: Future[HttpResponse]){
//   // wrapper class around a httpResponse future
//
//   def getResData(res: HttpResponse, cb:(String)  =>  Unit) = {
//     val data = res.entity.dataBytes.runWith(Sink.head)
//     data.map(byteSeq => byteSeq.map(b => b.toChar)).map(charSeq => {
//       cb(charSeq.mkString)
//     })
//   }
// }

class Request(baseUri: String, isHttps: Boolean = false){
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

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

  private def getFormURLEncoded(params: Map[String, String]): String = {
    val sortedParams = SortedMap(params.toList:_*)
    sortedParams.map { paramTuple =>
      java.net.URLEncoder.encode(paramTuple._1, "UTF-8") + "=" + java.net.URLEncoder.encode(paramTuple._2, "UTF-8")
    }.mkString("&")
  }

  def get(uri: String, params: Map[String, String] = Map(), method: HttpMethod=HttpMethods.GET,
          oauth: Oauth=new Oauth("", "")): Future[HttpResponse] = {
    var headers = List(RawHeader("Accept", "*/*"))

    if (oauth.canSignRequests()) {
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

    Source.single(HttpRequest(uri = uri + queryParams,
      method=method,
      headers=headers)
     ).via(_outgoingConn).runWith(Sink.head)
  }

  def post(uri: String, params: Map[String, String]=Map(), oauth: Oauth=new Oauth("", ""), json: Boolean=true): Future[HttpResponse] = {

    var headers = List(RawHeader("Accept", "*/*"))

    if (oauth.hasKeys && !oauth.canSignRequests()) {
      if (oauth.authProgress == AuthProgress.NotAuthed) {
        headers = List(RawHeader("Authorization", oauth.getRequestTokenHeader(_netLoc+baseUri+uri)), RawHeader("Accept", "*/*"))
      } else if (oauth.authProgress == AuthProgress.HasRequestTokens) {
        headers = List(RawHeader("Authorization", oauth.getAccessTokenHeader(_netLoc+baseUri+uri)), RawHeader("Accept", "*/*"))
      }
    } else if (oauth.canSignRequests()) {
      headers = List(RawHeader("Authorization", oauth.getSignedHeader(_netLoc+baseUri+uri, "POST", params)), RawHeader("Accept", "*/*"))
    }

    val entity = if (params.size > 0 && !oauth.canSignRequests() && json) {
        val paramStr = ByteString(params.toJson.toString)
        HttpEntity(contentType=ContentTypes.`application/json`, contentLength=paramStr.length, Source(List(paramStr)))
    } else if ((params.size > 0 && json == false)|| oauth.canSignRequests()) {

      // application/x-www-form-urlencoded
      val paramStr = ByteString(getFormURLEncoded(params))
      HttpEntity(contentType=ContentType(MediaTypes.`application/x-www-form-urlencoded`), contentLength=paramStr.length, Source(List(paramStr)))
    } else {
      HttpEntity.Empty
    }

    val postRequest = HttpRequest(
      uri=uri,
      method=HttpMethods.POST,
      headers = headers,
      entity=entity)

    Source.single(postRequest).via(_outgoingConn).runWith(Sink.head)
  }

  def delete(uri: String): Future[HttpResponse] =
    get(uri=uri, method=HttpMethods.DELETE)
}
