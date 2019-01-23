package com.drobisch.tresor.vault

import com.drobisch.tresor.Provider
import com.softwaremill.sttp._
import io.circe.generic.auto._
import cats.syntax.either._ // shadow either for Scala 2.11
import cats.effect.Sync
import org.slf4j.LoggerFactory

final case class KeyValueContext(key: String, vaultConfig: VaultConfig)

private[vault] final case class KeyValueResponseDTO(
  lease_id: Option[String],
  renewable: Boolean,
  lease_duration: Option[Long],
  data: Map[String, Option[String]])

class KV[F[_]] extends Provider[F, KeyValueContext, Lease] with HttpSupport {
  private val log = LoggerFactory.getLogger(getClass)

  override def secret(context: KeyValueContext)(implicit sync: Sync[F]): F[Lease] = {
    val response = sttp
      .get(uri"${context.vaultConfig.apiUrl}/secret/${context.key}")
      .header("X-Vault-Token", context.vaultConfig.token)
      .send()

    log.debug("response from vault: {}", response)

    sync.flatMap(sync.fromEither(response.body.left.map(new RuntimeException(_)))) { body =>
      sync.delay {
        val lease = fromDto(io.circe.parser.decode[KeyValueResponseDTO](body).right.get)
        log.debug("returning lease: {}", lease)
        lease
      }
    }
  }

  protected def fromDto(dto: KeyValueResponseDTO): Lease = {
    Lease(
      leaseId = dto.lease_id,
      data = dto.data,
      renewable = dto.renewable,
      leaseDuration = dto.lease_duration)
  }
}

object KV {
  def apply[F[_]] = new KV[F]
}