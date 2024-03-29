package com.commercetools.tresor.vault

import cats.effect.IO
import com.commercetools.tresor.{StepClock, WireMockSupport, vault}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Random
import sttp.client3._
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.EncoderOps

class KVSpec extends AnyFlatSpec with Matchers with WireMockSupport {
  val log: Logger = LoggerFactory.getLogger(getClass)

  "KV provider" should "read token from vault KV engine (uses Mock)" in {
    import com.github.tomakehurst.wiremock.client.WireMock._

    val result: Lease = withWireMock(server =>
      IO.delay {
        val vaultSecretResponse = aResponse()
          .withStatus(200)
          .withBody(
            s"""{"request_id":"1","lease_id":"","renewable":false,"lease_duration":43200,"data":{"key":"value"},"wrap_info":null,"warnings":null,"auth":null}"""
          )

        val vaultSecretRequest = get(urlEqualTo("/v1/secret/treasure"))
          .withHeader("X-Vault-Token", equalTo("vault-token"))
          .willReturn(vaultSecretResponse)

        server.stubFor(vaultSecretRequest)
      }.flatMap { _ =>
        val vaultConfig = VaultConfig(
          apiUrl = s"http://localhost:${server.port()}/v1",
          token = "vault-token"
        )
        implicit val clock = StepClock(1)

        vault
          .KV[IO]("secret")
          .secret(KVContext(secretPath = "treasure"), vaultConfig)
      }
    ).unsafeRunSync()

    result should be(
      Lease(
        leaseId = Some(""),
        data = Some(Map("key" -> Some("value")).asJson),
        renewable = false,
        leaseDuration = Some(43200),
        1
      )
    )
  }

  "KV provider" should "create, update and read a new token from vault KV engine (uses Docker Vault)" in {
    val config =
      VaultConfig("http://0.0.0.0:8200/v1", "vault-plaintext-root-token")

    val keyValueV1Mount =
      s"""
        |{
        |  "type": "kv",
        |  "config": {
        |    "version": "1"
        |  }
        |}""".stripMargin

    sttp.client3.basicRequest
      .post(uri"${config.apiUrl}/sys/mounts/secret-v1")
      .header("X-Vault-Token", config.token)
      .body(keyValueV1Mount)
      .send(HttpURLConnectionBackend(SttpBackendOptions.Default))

    val secretName = Random.alphanumeric.take(10).mkString

    def createKvSecret: IO[Unit] =
      KV[cats.effect.IO]("secret-v1").createOrUpdate(
        (KVContext(secretPath = secretName), config),
        Map("foo" -> Some("bar"))
      )

    def updateKvSecret: IO[Unit] =
      KV[cats.effect.IO]("secret-v1").createOrUpdate(
        (KVContext(secretPath = secretName), config),
        Map("foo" -> Some("baz"))
      )

    def kvSecret: IO[Lease] =
      KV[cats.effect.IO]("secret-v1")
        .secret(KVContext(secretPath = secretName), config)

    val (secret1, secret2) = (for {
      _ <- createKvSecret
      secret1 <- kvSecret
      _ <- updateKvSecret
      secret2 <- kvSecret
    } yield (secret1, secret2)).unsafeRunSync()

    secret1.data should be(
      Some(Json.obj("foo" -> "bar".asJson))
    )
    secret2.data should be(
      Some(Json.obj("foo" -> "baz".asJson))
    )
  }
}
