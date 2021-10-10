package com.drobisch.tresor.vault

import cats.effect.IO
import com.drobisch.tresor.WireMockSupport
import com.github.tomakehurst.wiremock.client.WireMock.{
  aResponse,
  post,
  urlEqualTo
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import cats.effect.unsafe.implicits.global

class AuthenticationSpec
    extends AnyFlatSpec
    with Matchers
    with WireMockSupport {
  "Authentication" should "login for GCE" in {
    import io.circe.generic.auto._

    val serverResponse =
      s"""
         |{
         |  "request_id": "1",
         |  "lease_id": "",
         |  "renewable": false,
         |  "auth": {
         |    "client_token": "newtoken",
         |    "metadata": {
         |      "role": "somerole"
         |    },
         |    "lease_duration": 1,
         |    "renewable": true
         |  }
         |}
       """.stripMargin

    withWireMock(server =>
      IO.delay {
        val vaultCredentialsResponse = aResponse()
          .withStatus(200)
          .withBody(serverResponse)

        val vaultCredentialsRequest = post(urlEqualTo("/v1/auth%2Fgce/login"))
          .willReturn(vaultCredentialsResponse)

        server.stubFor(vaultCredentialsRequest)
      }.flatMap { _ =>
        VaultAuthentication
          .login[IO, GcpLoginRequest, GcpLoginResponse](
            vaultUrl = s"http://localhost:${server.port()}/v1",
            authPath = "auth/gce",
            request = GcpLoginRequest("somerole", "somejwt")
          )
      }
    ).unsafeRunSync() should be(
      GcpLoginResponse(
        GcpAuth(
          client_token = "newtoken",
          GcpAuthMetadata("somerole"),
          1,
          renewable = true
        )
      )
    )
  }
}
