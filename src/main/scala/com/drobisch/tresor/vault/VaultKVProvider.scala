package com.drobisch.tresor.vault

import cats.effect.IO
import com.drobisch.tresor.Provider
import com.softwaremill.sttp._
import io.circe._

import cats.syntax.either._ // shadow either methods for Scala 2.11

case class KeyValueContext(vaultConfig: VaultConfig)

trait VaultKVProvider extends Provider[IO, KeyValueContext, Lease] {
  implicit val backend = HttpURLConnectionBackend()

  override def getSecret(key: String, context: KeyValueContext): IO[Lease] = {
      val response = sttp
        .get(uri"${context.vaultConfig.apiUrl}/secret/$key")
        .header("X-Vault-Token", context.vaultConfig.token)
        .send()

      IO.fromEither(response.body.left.map(new RuntimeException(_))).map { body =>
        val dataFields: Map[String, String] = io.circe.parser.decode[Json](body).right
          .get.asObject
          .get.apply("data")
          .get.as[Map[String, String]]
          .getOrElse(Map.empty)

        Lease(dataFields)
      }
  }
}