package com.drobisch.tresor

import cats.effect.IO
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

trait WireMockSupport {
  def withWireMock[T](block: WireMockServer => IO[T]): IO[T] = IO.delay {
    val wireMockServer = new WireMockServer(wireMockConfig.dynamicPort())
    wireMockServer.start()
    wireMockServer
  }.flatMap(server => block(server).guarantee(IO.delay(server.stop())))
}
