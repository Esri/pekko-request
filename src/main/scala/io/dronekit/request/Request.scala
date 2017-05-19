package io.dronekit.request

import java.time.{Instant, Duration => JavaDuration}
import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer
import akka.util.ByteString
import cloud.drdrdr.oauth.{Oauth, AuthProgress}
import akka.http.scaladsl.unmarshalling.{ Unmarshal, Unmarshaller }
import akka.http.scaladsl.marshalling.{ Marshal, Marshaller }

import scala.collection.immutable.SortedMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

trait RequestLogger {
  def log(request: HttpRequest, response: HttpResponse, latency: JavaDuration)
  def logTimeout(request: HttpRequest)
}

object NullLogger extends RequestLogger {
  def log(request: HttpRequest, response: HttpResponse, latency: JavaDuration) = {}
  def logTimeout(request: HttpRequest) = {}
}

object PrintLogger extends RequestLogger {  
  def printEntity(entity: HttpEntity) = {
    if (!entity.isKnownEmpty) {
      val lenStr = entity.contentLengthOption.map(l => s"${l} bytes").getOrElse("unknown size")
      entity.contentType match {
        case ct: ContentType.Binary => println(s"\t(${ct}, ${lenStr})")
        case ct: ContentType.NonBinary => {
          entity match {
            case strict: HttpEntity.Strict => {
              val s = strict.data.decodeString(ct.charset.value)
              if (!s.isEmpty) {
                println("\t| " + s.replace("\n", "\n\t| "))
              }
            }
            case ct => println(s"\t(${ct}, ${lenStr})")
          }
        }
      }
    }
  }
  
  def logRequest(request: HttpRequest) = {
    println(s"<~ ${request.method.value} ${request.getUri}")
    printEntity(request.entity)
  }
  
  def log(request: HttpRequest, response: HttpResponse, latency: JavaDuration) = {
    logRequest(request)
    println(s"~> ${response.status} in ${latency.toMillis}ms")
    printEntity(response.entity)
  }
  
  def logTimeout(request: HttpRequest) = {
    logRequest(request)
    println(s"~! request timed out")
  }
}

object Client {
  def apply(baseUri: String, logger: RequestLogger = NullLogger)
    (implicit materializer: ActorMaterializer, system: ActorSystem) = {
    require(baseUri.startsWith("http"))
    val uri = java.net.URI.create(baseUri)
    var httpTimeout = 60.seconds
    val hostname = uri.getHost
    val httpScheme = if (uri.getScheme == null) "https" else uri.getScheme
    val port = if (uri.getPort == -1) (if (httpScheme == "http") 80 else 443) else uri.getPort
    val outgoingConn = if (httpScheme == "https") {
      Http().outgoingConnectionHttps(hostname, port)
    } else {
      Http().outgoingConnection(hostname, port)
    }
    
    new Client(baseUri, outgoingConn, logger)
  }
  
  def retry[T](retries: Int = 3)(req: => Future[T]): Future[T] = {
    val p = Promise[T]()

    def retryHelper(retryNum: Int = retries): Unit = {
      // catch timeout errors
      req.onComplete{
        case Success(v) => p.success(v)
        case Failure(ex: TimeoutException) if retryNum > 0 => retryHelper(retryNum - 1)
        case Failure(ex) => p.failure(ex)
      }
    }

    retryHelper()
    p.future
  }
}

case class ClientRequestError(status: StatusCode, body: ByteString) extends RuntimeException(s"Request failed with status ${status}: ${body.utf8String}")

final class Client(
  val baseUri: String,
  val outgoingConn: Flow[HttpRequest, HttpResponse, Any],
  val logger: RequestLogger = NullLogger,
  val timeout: FiniteDuration = 60.seconds,
  val oauth: Oauth = new Oauth("", ""),
  val defaultHeaders: List[HttpHeader] = List())
  (implicit materializer: ActorMaterializer, system: ActorSystem) {
  
  def withOauth(newOauth: Oauth) = {
    new Client(baseUri, outgoingConn, logger, timeout, newOauth, defaultHeaders)
  }
  
  def withTimeout(newTimeout: FiniteDuration) = {
    new Client(baseUri, outgoingConn, logger, newTimeout, oauth, defaultHeaders)
  }
  
  def withHeader(newHeader: HttpHeader) = {
    new Client(baseUri, outgoingConn, logger, timeout, oauth, defaultHeaders :+ newHeader)
  }
  
  def withHeaders(newHeaders: Seq[HttpHeader]) = {
    new Client(baseUri, outgoingConn, logger, timeout, oauth, defaultHeaders ++ newHeaders)
  }
  
  private def getFormURLEncoded(params: Map[String, String]): String = {
    val sortedParams = SortedMap(params.toList:_*)
    sortedParams.map { paramTuple =>
      java.net.URLEncoder.encode(paramTuple._1, "UTF-8") + "=" + java.net.URLEncoder.encode(paramTuple._2, "UTF-8")
    }.mkString("&")
  }
  
  def request(req: HttpRequest): Future[HttpResponse] = {
    val startTime = Instant.now
    Source.single(req)
      .via(outgoingConn)
      .completionTimeout(timeout)
      .runWith(Sink.head)
      .andThen {
          case Success(res) => logger.log(req, res, JavaDuration.between(startTime, Instant.now))
          case Failure(_ : TimeoutException) => logger.logTimeout(req)
      }
  }
  
  def parseJsonResponse[T](resp: HttpResponse)(implicit um: Unmarshaller[ResponseEntity, T]): Future[T] = {
    resp.status match {
       // TODO: make this optional?
      case success: StatusCodes.Success => Unmarshal(resp.entity.withContentType(ContentTypes.`application/json`)).to[T]
      case other => resp.entity.toStrict(timeout).flatMap { strictEntity => 
        Future.failed(ClientRequestError(other, strictEntity.data))
      }
    }
  }
  
  def oauthHeaders(path: String, method: HttpMethod, params: Map[String, String] = Map()): List[RawHeader] = {
    if (oauth.canSignRequests) {
      List(RawHeader("Authorization", oauth.getSignedHeader(baseUri+path, method.value, params)))
    } else if (oauth.hasKeys) {
      if (oauth.authProgress == AuthProgress.Unauthenticated) {
        List(RawHeader("Authorization", oauth.getRequestTokenHeader(baseUri+path)))
      } else if (oauth.authProgress == AuthProgress.HasRequestTokens || oauth.authProgress == AuthProgress.RequestRefreshTokens) {
        List(RawHeader("Authorization", oauth.getAccessTokenHeader(baseUri+path)))
      } else {
        List()
      }
    } else {
      List()
    }
  }
  
  def get[Res](path: String, params: Map[String, String] = Map(), headers: Seq[HttpHeader] = Seq())
    (implicit um: Unmarshaller[ResponseEntity, Res]): Future[Res] = {
    val allHeaders = defaultHeaders ++ oauthHeaders(path, HttpMethods.GET, params) ++ headers
    val queryParams = if (params.nonEmpty) { "?" + getFormURLEncoded(params) } else { "" }
    request(HttpRequest(uri = baseUri + path + queryParams, method=HttpMethods.GET, headers=allHeaders)).flatMap(parseJsonResponse(_))
  }
  
  def post[Req, Res](path: String, body: Req, headers: Seq[HttpHeader] = Seq())
    (implicit m: Marshaller[Req, RequestEntity], um: Unmarshaller[ResponseEntity, Res]): Future[Res] = {
    val allHeaders = defaultHeaders ++ oauthHeaders(path, HttpMethods.POST) ++ headers
    for {
      entity <- Marshal(body).to[MessageEntity]
      response <- request(HttpRequest(uri = baseUri + path, method=HttpMethods.POST, entity=entity, headers=allHeaders))
      parsedResponse <- parseJsonResponse(response)
    } yield parsedResponse
  }
  
  def delete[Res](path: String, params: Map[String, String] = Map(), headers: Seq[HttpHeader] = Seq())
    (implicit um: Unmarshaller[ResponseEntity, Res]): Future[Res] = {
    val allHeaders = defaultHeaders ++ oauthHeaders(path, HttpMethods.DELETE, params) ++ headers
    val queryParams = if (params.nonEmpty) { "?" + getFormURLEncoded(params) } else { "" }
    request(HttpRequest(uri = baseUri + path + queryParams, method=HttpMethods.DELETE, headers=allHeaders)).flatMap(parseJsonResponse(_))
  }
  
  def put[Req, Res](path: String, body: Req, headers: Seq[HttpHeader] = Seq())
    (implicit m: Marshaller[Req, RequestEntity], um: Unmarshaller[ResponseEntity, Res]): Future[Res] = {
    val allHeaders = defaultHeaders ++ oauthHeaders(path, HttpMethods.PUT) ++ headers
    for {
      entity <- Marshal(body).to[MessageEntity]
      response <- request(HttpRequest(uri = baseUri + path, method=HttpMethods.PUT, entity=entity, headers=allHeaders))
      parsedResponse <- parseJsonResponse(response)
    } yield parsedResponse
  }
  
  def requestUrlEncoded[Res](method: HttpMethod, path: String, params: Map[String, String], headers: Seq[HttpHeader] = Seq())
    (implicit um: Unmarshaller[ResponseEntity, Res]): Future[Res] = {
    val allHeaders = defaultHeaders ++ oauthHeaders(path, method, params) ++ headers
    val paramStr = ByteString(getFormURLEncoded(params))
    val contentType = ContentType(MediaTypes.`application/x-www-form-urlencoded`, HttpCharsets.`UTF-8`)
    val entity = HttpEntity.Strict(contentType, paramStr)
    request(HttpRequest(uri = baseUri + path, method=method, entity=entity, headers=allHeaders)).flatMap(parseJsonResponse(_))
  }
  
  def postUrlEncoded[Res](path: String, params: Map[String, String], headers: Seq[HttpHeader] = Seq())
    (implicit um: Unmarshaller[ResponseEntity, Res]): Future[Res] = {
    requestUrlEncoded(HttpMethods.POST, path, params, headers)
  }
  
  def putUrlEncoded[Res](path: String, params: Map[String, String], headers: Seq[HttpHeader] = Seq())
    (implicit um: Unmarshaller[ResponseEntity, Res]): Future[Res] = {
    requestUrlEncoded(HttpMethods.PUT, path, params, headers)
  }
}
