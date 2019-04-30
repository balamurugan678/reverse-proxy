package com.poc.sample

object ReverseSwinger {

  def main(args: Array[String]) {
    println("\nListening on http://0.0.0.0:8080 ...\n")
    new CatsReverseProxy().start("localhost", 8080)
  }

}
