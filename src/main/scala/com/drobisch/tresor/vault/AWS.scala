package com.drobisch.tresor.vault

import cats.effect.Sync
import io.circe.generic.auto._
import com.drobisch.tresor.Provider
import com.softwaremill.sttp._
import org.slf4j.LoggerFactory

// shadow either for Scala 2.11
import cats.syntax.either._

import io.circe.Json

final case class AwsContext(
  name: String,
  roleArn: Option[String] = None,
  ttlString: Option[String] = None,
  useSts: Boolean = false,
  vaultConfig: VaultConfig)

private[vault] final case class AwsResponseDTO(
  lease_id: Option[String],
  renewable: Boolean,
  lease_duration: Option[Long],
  data: Option[Map[String, Option[String]]])

class AWS[F[_]] extends Provider[F, AwsContext, Lease] with HttpSupport {
  private val log = LoggerFactory.getLogger(getClass)

  override def secret(awsContext: AwsContext)(implicit sync: Sync[F]): F[Lease] = sync.flatMap(sync.delay {
    val roleArnPart = awsContext.roleArn.map(s"&role_arn=" + _).getOrElse("")
    val ttlPart = "&ttl=" + awsContext.ttlString.getOrElse("3600s")
    val infixPart = if (awsContext.useSts) "sts" else "creds"

    val requestUri = s"${awsContext.vaultConfig.apiUrl}/aws/$infixPart/${awsContext.name}?$roleArnPart$ttlPart"

    sttp
      .get(uri"$requestUri")
      .header("X-Vault-Token", awsContext.vaultConfig.token)
      .send()
  }) { response =>
    log.debug("response from vault: {}", response)
    parseLease(response)
  }

  def renew(lease: Lease, increment: Long, vaultConfig: VaultConfig)(implicit sync: Sync[F]): F[Lease] = lease.leaseId.map { leaseId =>
    sync.flatMap(sync.delay {
      sttp
        .post(uri"${vaultConfig.apiUrl}/sys/leases/renew")
        .body(Json.obj("lease_id" -> Json.fromString(leaseId), "increment" -> Json.fromLong(increment)).noSpaces)
        .header("X-Vault-Token", vaultConfig.token)
        .send()
    }) { response =>
      log.debug("response from vault: {}", response)
      parseLease(response)
    }
  }.getOrElse(sync.raiseError(new IllegalArgumentException("no lease id defined")))

  protected def parseLease(response: Response[String])(implicit sync: Sync[F]): F[Lease] = {
    sync.flatMap(sync.fromEither(response.body.left.map(new RuntimeException(_)))) { body =>
      sync.delay {
        val lease = fromDto(io.circe.parser.decode[AwsResponseDTO](body).right.get)
        log.debug("returning lease: {}", lease)
        lease
      }
    }
  }

  protected def fromDto(dto: AwsResponseDTO): Lease = {
    Lease(
      leaseId = dto.lease_id,
      data = dto.data.getOrElse(Map.empty),
      renewable = dto.renewable,
      leaseDuration = dto.lease_duration)
  }
}

object AWS {
  def apply[F[_]] = new AWS[F]
}
