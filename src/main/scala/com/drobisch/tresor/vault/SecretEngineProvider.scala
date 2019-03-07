package com.drobisch.tresor.vault

import java.util.concurrent.TimeUnit

import cats.data.ReaderT
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

abstract class SecretEngineProvider[Effect[_], ProviderContext](implicit sync: Sync[Effect], clock: Clock[Effect]) extends Provider[Effect, ProviderContext, Lease] with HttpSupport {
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
  def renew(lease: Lease, increment: Option[Long]): ReaderT[Effect, VaultConfig, Lease] = lease.leaseId.map { leaseId =>
    ReaderT[Effect, VaultConfig, Lease] { vaultConfig =>
      sync.flatMap(sync.delay {
        sttp
          .post(uri"${vaultConfig.apiUrl}/sys/leases/renew")
          .body(Json.obj("lease_id" -> Json.fromString(leaseId), "increment" -> Json.fromLong(increment.getOrElse(3600))).noSpaces)
          .header("X-Vault-Token", vaultConfig.token)
          .send()
      }) { response =>
        log.debug("response from vault: {}", response)
        parseLease(response).map(renewed => renewed.copy(data = lease.data))
      }
    }
  }.getOrElse(ReaderT[Effect, VaultConfig, Lease](_ => sync.raiseError(new IllegalArgumentException("no lease id defined"))))

  /**
   * Auto refresh a lease reference based on the current time.
   *
   * This is not a continuous refresh, the flow is:
   *
   * 1. if lease is not renewable or is expired, create a new one
   * 2. if its not expired but half of the duration time has passed more time
   * then configured by the refresh ratio: refresh the the lease
   * 3. return current lease otherwise
   *
   * @param leaseRef a reference to the current (maybe empty) lease
   * @param increment the increment to request in the renew
   * @param refreshRatio the ratio of duration time that is allowed to pass before refreshing the lease
   * @param newLease the fallback effect reader for creating a new lease
   * @return an effect reader with the logic above applied
   */
  def autoRefresh(
    leaseRef: Ref[Effect, Option[Lease]],
    increment: Option[Long] = None,
    refreshRatio: Double = 0.5,
    forceNew: Boolean = false)(newLease: ReaderT[Effect, VaultConfig, Lease]): ReaderT[Effect, VaultConfig, Lease] = {
    for {
      now <- ReaderT.liftF(clock.realTime(TimeUnit.SECONDS))
      currentLease <- ReaderT.liftF(leaseRef.get)
      valid <- {
        currentLease match {
          case Some(lease) =>
            val duration = lease.leaseDuration.getOrElse(0L)
            val expiryTime = lease.issueTime + duration
            val ratioTime = expiryTime - duration * refreshRatio

            if (!lease.renewable || now >= expiryTime) {
              newLease
            } else if (now >= ratioTime && !forceNew) {
              renew(lease, increment)
            } else if (now >= ratioTime && forceNew) {
              newLease
            } else ReaderT[Effect, VaultConfig, Lease](_ => sync.pure(lease))

          case None => newLease
        }
      }
      updated <- ReaderT[Effect, VaultConfig, Lease](_ => leaseRef.set(Some(valid)) *> sync.delay(valid))
    } yield updated
  }

  protected def parseLease(response: Response[String]): Effect[Lease] = {
    for {
      now <- clock.realTime(TimeUnit.SECONDS)
      body <- sync.fromEither(response.body.left.map(new RuntimeException(_)))
    } yield {
      val lease = fromDto(io.circe.parser.decode[LeaseDTO](body).right.get, now)
      log.debug("parsed lease: {}", lease)
      lease
    }
  }

  protected def fromDto(dto: LeaseDTO, now: Long): Lease = {
    Lease(
      leaseId = dto.lease_id,
      data = dto.data.getOrElse(Map.empty),
      renewable = dto.renewable,
      leaseDuration = dto.lease_duration,
      issueTime = now)
  }
}
