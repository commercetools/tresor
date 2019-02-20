package com.drobisch.tresor.vault

import cats.effect.IO
import com.drobisch.tresor.WireMockSupport
import com.github.tomakehurst.wiremock.client.WireMock.{ aResponse, equalTo, get, urlEqualTo }
import org.scalatest.{ FlatSpec, Matchers }

class DatabaseSpec extends FlatSpec with Matchers with WireMockSupport {
  "Database provider" should "read credentials" in {
    val serverResponse =
      s"""
         |{"request_id":"1","lease_id":"database/creds/role/1","renewable":true,"lease_duration":3600,"data":{"password":"thepw","username":"theuser"},"wrap_info":null,"warnings":null,"auth":null}
       """.stripMargin

    val result: Lease = withWireMock(server => IO.delay {
      val vaultCredentialsResponse = aResponse()
        .withStatus(200)
        .withBody(serverResponse)

      val vaultCredentialsRequest = get(urlEqualTo("/v1/database/creds/role"))
        .withHeader("X-Vault-Token", equalTo("vault-token"))
        .willReturn(vaultCredentialsResponse)

      server.stubFor(vaultCredentialsRequest)
    }.flatMap { _ =>
      val vaultConfig = VaultConfig(apiUrl = s"http://localhost:${server.port()}/v1", token = "vault-token")
      val dbContext = DatabaseContext("role", vaultConfig)

      Database[IO].secret(dbContext)
    }).unsafeRunSync()

    val expectedLease = Lease(
      leaseId = Some("database/creds/role/1"),
      data = Map("username" -> Some("theuser"), "password" -> Some("thepw")),
      renewable = true,
      leaseDuration = Some(3600))

    result should be(expectedLease)
  }
}
