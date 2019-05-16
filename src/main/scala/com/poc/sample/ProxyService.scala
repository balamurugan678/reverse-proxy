package com.poc.sample


import akka.actor.ActorSystem
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.ActorMaterializer
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import scala.concurrent.{ExecutionContextExecutor, Future}

trait ProxyService {
  implicit val system: ActorSystem

  implicit def executor: ExecutionContextExecutor

  implicit val materializer: ActorMaterializer
  implicit val http: HttpExt


  def proxyTo(request: HttpRequest): Future[HttpResponse] = {


    val proxyRequest = request.copy(
      uri = request.uri.copy(
        scheme = "http",
        authority = Authority(host = Uri.NamedHost("localhost"), port = 9200)
      )
    )

    http.singleRequest(proxyRequest).flatMap { response =>
      response.entity.withoutSizeLimit.dataBytes
        .runFold(ByteString(""))(_ ++ _)
        .flatMap(byteString => Gzip.decode(byteString))
        .map(_.utf8String)
        .map { respString => {
          HttpResponse(
            StatusCodes.OK,
            entity = HttpEntity(ContentTypes.`application/json`, respString)
          )
        }
        }
    }

  }

  def proxyRoutes() = {
    pathPrefix("") {
      extractRequest { httpRequest =>
        complete {
          proxyTo(httpRequest)
        }
      }
    }
  }

}

object ReverseProxy extends App with ProxyService {
  override implicit val system = ActorSystem()
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorMaterializer()
  override implicit val http = Http(system)


  val config = ConfigFactory.load()

  val appRoutes = proxyRoutes()

  Http().bindAndHandle(appRoutes, "localhost", 8080)
}