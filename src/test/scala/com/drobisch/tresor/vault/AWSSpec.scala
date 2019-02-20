package com.drobisch.tresor.vault

import cats.effect.IO
import com.drobisch.tresor.{ WireMockSupport, vault }
import org.scalatest.{ FlatSpec, Matchers }

class AWSSpec extends FlatSpec with Matchers with WireMockSupport {
  "AWS provider" should "create credentials from vault AWS engine" in {
    import com.github.tomakehurst.wiremock.client.WireMock._

    val result: Lease = withWireMock(server => IO.delay {
      val vaultCredentialsResponse = aResponse()
        .withStatus(200)
        .withBody(s"""{"request_id":"1","lease_id":"aws/creds/some-role/abcd-123456","renewable":true,"lease_duration":43200,"data":{"access_key":"key","secret_key":"secret","security_token":null},"wrap_info":null,"warnings":null,"auth":null}""")

      val vaultCredentialsRequest = get(urlEqualTo("/v1/aws/creds/some-role?&ttl=3600s"))
        .withHeader("X-Vault-Token", equalTo("vault-token"))
        .willReturn(vaultCredentialsResponse)

      server.stubFor(vaultCredentialsRequest)
    }.flatMap { _ =>
      val vaultConfig = VaultConfig(apiUrl = s"http://localhost:${server.port()}/v1", token = "vault-token")

      vault.AWS[IO].createCredentials(AwsContext(name = "some-role", vaultConfig = vaultConfig))
    }).unsafeRunSync()

    val expectedLease = Lease(
      leaseId = Some("aws/creds/some-role/abcd-123456"),
      data = Map("access_key" -> Some("key"), "secret_key" -> Some("secret"), "security_token" -> None),
      renewable = true,
      leaseDuration = Some(43200))

    result should be(expectedLease)
  }

  it should "renew lease" in {
    import com.github.tomakehurst.wiremock.client.WireMock._

    val result: Lease = withWireMock(server => IO.delay {
      val vaultRenewResponse = aResponse()
        .withStatus(200)
        .withBody(s"""{"request_id":"1","lease_id":"aws/creds/some-role/abcd-123456","renewable":true,"lease_duration":60,"data":null,"wrap_info":null,"warnings":null,"auth":null}""")

      val vaultRenewRequest = post(urlEqualTo("/v1/sys/leases/renew"))
        .withRequestBody(equalTo(s"""{"lease_id":"","increment":60}"""))
        .withHeader("X-Vault-Token", equalTo("vault-token"))
        .willReturn(vaultRenewResponse)

      server.stubFor(vaultRenewRequest)
    }.flatMap { _ =>
      val vaultConfig = VaultConfig(apiUrl = s"http://localhost:${server.port()}/v1", token = "vault-token")
      val existingLease = Lease(leaseId = Some(""), renewable = true, data = Map.empty, leaseDuration = Some(60))

      AWS[IO].renew(existingLease, increment = 60, vaultConfig)
    }).unsafeRunSync()

    val expectedLease = Lease(
      leaseId = Some("aws/creds/some-role/abcd-123456"),
      data = Map.empty,
      renewable = true,
      leaseDuration = Some(60))

    result should be(expectedLease)
  }

}
