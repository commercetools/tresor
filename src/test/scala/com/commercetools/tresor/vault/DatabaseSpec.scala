package com.commercetools.tresor.vault

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.commercetools.tresor.{StepClock, WireMockSupport}
import com.github.tomakehurst.wiremock.client.WireMock.{
  aResponse,
  equalTo,
  get,
  urlEqualTo
}
import io.circe.syntax.EncoderOps
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DatabaseSpec extends AnyFlatSpec with Matchers with WireMockSupport {
  "Database provider" should "read credentials" in {
    val serverResponse =
      s"""
         |{"request_id":"1","lease_id":"database/creds/role/1","renewable":true,"lease_duration":3600,"data":{"password":"thepw","username":"theuser"},"wrap_info":null,"warnings":null,"auth":null}
       """.stripMargin

    val result: Lease = withWireMock(server =>
      IO.delay {
        val vaultCredentialsResponse = aResponse()
          .withStatus(200)
          .withBody(serverResponse)

        val vaultCredentialsRequest = get(urlEqualTo("/v1/database/creds/role"))
          .withHeader("X-Vault-Token", equalTo("vault-token"))
          .willReturn(vaultCredentialsResponse)

        server.stubFor(vaultCredentialsRequest)
      }.flatMap { _ =>
        val vaultConfig = VaultConfig(
          apiUrl = s"http://localhost:${server.port()}/v1",
          token = "vault-token"
        )
        val dbContext = DatabaseContext("role")
        implicit val clock = StepClock(1)

        Database[IO]("database").secret(dbContext, vaultConfig)
      }
    ).unsafeRunSync()

    val expectedLease = Lease(
      leaseId = Some("database/creds/role/1"),
      data = Some(
        Map("username" -> Some("theuser"), "password" -> Some("thepw")).asJson
      ),
      renewable = true,
      leaseDuration = Some(3600),
      1
    )

    result should be(expectedLease)
  }
}
