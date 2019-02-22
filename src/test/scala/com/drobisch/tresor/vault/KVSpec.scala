package com.drobisch.tresor.vault

import cats.effect.IO
import com.drobisch.tresor.{ StepClock, WireMockSupport, vault }
import org.scalatest.{ FlatSpec, Matchers }
import org.slf4j.{ Logger, LoggerFactory }

class KVSpec extends FlatSpec with Matchers with WireMockSupport {
  val log: Logger = LoggerFactory.getLogger(getClass)

  "KV provider" should "read token from vault KV engine" in {
    import com.github.tomakehurst.wiremock.client.WireMock._

    val result: Lease = withWireMock(server => IO.delay {
      val vaultSecretResponse = aResponse()
        .withStatus(200)
        .withBody(s"""{"request_id":"1","lease_id":"","renewable":false,"lease_duration":43200,"data":{"key":"value"},"wrap_info":null,"warnings":null,"auth":null}""")

      val vaultSecretRequest = get(urlEqualTo("/v1/secret/treasure"))
        .withHeader("X-Vault-Token", equalTo("vault-token"))
        .willReturn(vaultSecretResponse)

      server.stubFor(vaultSecretRequest)
    }.flatMap { _ =>
      val vaultConfig = VaultConfig(apiUrl = s"http://localhost:${server.port()}/v1", token = "vault-token")
      implicit val clock = StepClock(1)

      vault.KV[IO].secret(KeyValueContext(key = "treasure", vaultConfig))
    }).unsafeRunSync()

    result should be(Lease(leaseId = Some(""), data = Map("key" -> Some("value")), renewable = false, leaseDuration = Some(43200), 1))
  }
}
