package com.drobisch.tresor.vault

import java.util.concurrent.TimeUnit

import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.apply._
import cats.effect.{ Clock, Sync }
import cats.effect.concurrent.Ref
import com.drobisch.tresor.Provider
import com.softwaremill.sttp.{ Response, sttp }
import io.circe.Json
import io.circe.generic.auto._
import com.softwaremill.sttp._
import org.slf4j.LoggerFactory
import cats.syntax.either._ // shadow either for Scala 2.11

private[vault] final case class LeaseDTO(
  lease_id: Option[String],
  renewable: Boolean,
  lease_duration: Option[Long],
  data: Option[Map[String, Option[String]]])

trait SecretEngineProvider[Effect[_], ProviderContext] extends Provider[Effect, ProviderContext, Lease] with HttpSupport {
  protected val log = LoggerFactory.getLogger(getClass)

  /**
   * renew a lease
   *
   * https://www.vaultproject.io/api/system/leases.html#renew-lease
   *
   *
   * @param lease a lease
   * @param increment time to extend the lease in seconds
   * @param vaultConfig vault api config
   * @param sync context type
   * @return extended lease
   */
  def renew(lease: Lease, increment: Long, vaultConfig: VaultConfig)(implicit sync: Sync[Effect]): Effect[Lease] = lease.leaseId.map { leaseId =>
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

  def autoRefresh(lease: Effect[Lease])(implicit sync: Sync[Effect], clock: Clock[Effect]): Effect[Lease] = {
    val currentLeaseRef = Ref.unsafe[Effect, (Long, Lease)]((Long.MinValue, Lease(None, Map.empty, renewable = true, None)))

    for {
      now <- clock.realTime(TimeUnit.SECONDS)
      ref <- currentLeaseRef.get
      valid <- {
        val (issueTime, currentLease) = ref
        val expiryTime = issueTime + currentLease.leaseDuration.getOrElse(0L)

        if (currentLease.renewable && now >= expiryTime) {
          lease.flatMap(newLease => currentLeaseRef.set((now, newLease)) *> sync.delay(newLease))
        } else sync.pure(currentLease)
      }
    } yield valid
  }

  protected def parseLease(response: Response[String])(implicit sync: Sync[Effect]): Effect[Lease] = {
    sync.flatMap(sync.fromEither(response.body.left.map(new RuntimeException(_)))) { body =>
      sync.delay {
        val lease = fromDto(io.circe.parser.decode[LeaseDTO](body).right.get)
        log.debug("returning lease: {}", lease)
        lease
      }
    }
  }

  protected def fromDto(dto: LeaseDTO): Lease = {
    Lease(
      leaseId = dto.lease_id,
      data = dto.data.getOrElse(Map.empty),
      renewable = dto.renewable,
      leaseDuration = dto.lease_duration)
  }
}
