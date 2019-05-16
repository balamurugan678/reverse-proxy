package com.poc.sample


import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Host, RawHeader}
import akka.stream.ActorMaterializer
import akka.util.ByteString
import io.circe._
import models._

import scala.concurrent.Future
import scala.util.{Failure, Success}
import com.bazaarvoice.jolt.Chainr
import com.bazaarvoice.jolt.JsonUtils
import java.util
import java.util.List

import akka.stream.scaladsl.Sink
import com.sun.xml.internal.dtdparser.InputEntity

// Basic reverse proxy
class CatsReverseProxy {

  implicit val system = ActorSystem()
  implicit val executor = system.dispatcher
  implicit val materializer = ActorMaterializer()
  implicit val http = Http(system)

  def NotFound(path: String) = HttpResponse(
    404,
    entity = HttpEntity(ContentTypes.`application/json`, Json.obj("error" -> Json.fromString(s"$path not found")).noSpaces)
  )

  val services: Map[String, Target] = Map(
    "localhost" -> Target("http://localhost:9200")
  )


  def getHeaderValue(inputHeaders: Seq[HttpHeader]): String = {

    inputHeaders.filter(_.name().equalsIgnoreCase("Authorization")).headOption match {
      case Some(httpHeader) => httpHeader.value()
      case _ => null
    }
  }

  def extractHost(request: HttpRequest): String = request.header[Host].map(_.host.address()).getOrElse("--")

  def handler(request: HttpRequest): Future[HttpResponse] = {
    val host = extractHost(request)
    services.get(host) match {
      case Some(target) => {
        val proxyRequest = request.copy(
          uri = request.uri.copy(
            scheme = target.scheme,
            authority = Authority(host = Uri.NamedHost(target.host), port = target.port)
          )
        )
        val futureResponse = http.singleRequest(proxyRequest).flatMap { response =>
          response.entity.withoutSizeLimit.dataBytes
            .runFold(ByteString(""))(_ ++ _)
            .flatMap(byteString => Gzip.decode(byteString))
            .map(_.utf8String)
            .map { respString => {

              HttpResponse(
                StatusCodes.OK,
                entity = HttpEntity(ContentTypes.`application/json`, amendJson)
              )
            }
            }
        }
        futureResponse
        /*futureResponse.onComplete {
          case Success(res) => {
            val compressedByte: Future[ByteString] = for {
              byteString <- res.entity.dataBytes.runFold(ByteString(""))(_ ++ _)
              decompressedBytes <- Gzip.decode(byteString)
            } yield decompressedBytes
            val responseString: Future[String] = compressedByte.map(_.utf8String)
            responseString.onComplete {
              case Success(resp) => {
                println(resp)
                val inputJson = """{
                                  |	"id": 1,
                                  |	"animal": 2
                                  |}""".stripMargin
                val specString = """[
                                   |	{
                                   |		"operation": "remove",
                                   |		"spec": {
                                   |				"animal": ""
                                   |		}
                                   |	}
                                   |]""".stripMargin
                val chainrSpecJSON = JsonUtils.jsonToList(specString)
                val chainr = Chainr.fromSpec(chainrSpecJSON)
                val transformedOutput = chainr.transform(inputJson)
                println(JsonUtils.toPrettyJsonString(transformedOutput))
              }
              case Failure(ex) => ex.printStackTrace()
            }
          }
          case Failure(_) => sys.error("something wrong")
        }*/
        //val copyR = futureResponse.flatMap(response => Future(response.copy(status = response.status, headers = response.headers, entity=response.entity, protocol = response.protocol)))
        //copyR
      }
      case None => Future.successful(NotFound(host))
    }
  }

  def amendJson():String = {
    val chainrSpecJSON = JsonUtils.filepathToList("path/spec.json")
    val chainr = Chainr.fromSpec(chainrSpecJSON)
    val inputJSON = JsonUtils.filepathToObject("path/input.json")

    val transformed = chainr.transform(inputJSON)
    transformed.toString
  }

  def buildResponseEntity(inputEntity: ResponseEntity): Future[ResponseEntity] = {
    val compressedByte: Future[ByteString] = for {
      byteString <- inputEntity.dataBytes.runFold(ByteString(""))(_ ++ _)
      decompressedBytes <- Gzip.decode(byteString)
    } yield decompressedBytes
    val responseString: Future[String] = compressedByte.map(_.utf8String)
    val responseEntity: Future[ResponseEntity] = responseString.flatMap(response => Future(HttpEntity(ContentTypes.`application/json`, response)))
    responseEntity
  }

  def start(host: String, port: Int) {
    http.bindAndHandleAsync(handler, host, port)
  }
}