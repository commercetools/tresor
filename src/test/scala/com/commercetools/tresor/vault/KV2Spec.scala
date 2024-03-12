package com.commercetools.tresor.vault

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.commercetools.tresor.WireMockSupport
import io.circe.syntax.EncoderOps
import org.scalatest.Inside.inside
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Random

class KV2Spec extends AnyFlatSpec with Matchers with WireMockSupport {
  val log: Logger = LoggerFactory.getLogger(getClass)

  "KV2 provider" should "create, update and read a new token from vault KV2 engine (uses Docker Vault)" in {
    val config =
      VaultConfig("http://0.0.0.0:8200/v1", "vault-plaintext-root-token")

    // there is a default kv2 mount with path "secret" in the vault docker setup
    val mountPath = "secret"

    val secretName = Random.alphanumeric.take(10).mkString
    val kv2 = KV2[cats.effect.IO](mountPath)

    def createKvSecret: IO[KV2.Metadata] =
      kv2.createOrUpdate(
        (KV2Context(secretPath = secretName), config),
        Map("foo" -> Some("bar")).asJson
      )

    def updateKvSecret: IO[KV2.Metadata] =
      kv2.createOrUpdate(
        (KV2Context(secretPath = secretName), config),
        Map("foo" -> Some("baz")).asJson
      )

    def kvSecret: IO[Lease] =
      kv2.secret(KV2Context(secretPath = secretName), config)

    val (secret1, secret2) = (for {
      _ <- createKvSecret
      secret1 <- kvSecret
      _ <- updateKvSecret
      secret2 <- kvSecret
    } yield (secret1, secret2)).unsafeRunSync()

    inside(secret1.as[KV2.Secret[Map[String, String]]]) { secret1 =>
      secret1.map(_.data) should be(Right(Map("foo" -> "bar")))
      secret1.map(_.metadata.version) should be(Right(1))
    }

    inside(secret2.as[KV2.Secret[Map[String, String]]]) { secret2 =>
      secret2.map(_.data) should be(Right(Map("foo" -> "baz")))
    }
  }
}
