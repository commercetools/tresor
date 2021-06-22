package com.drobisch.tresor.vault

import cats.data.ReaderT
import cats.effect.concurrent.Ref
import cats.effect.{Clock, IO, Sync}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.drobisch.tresor.Provider
import io.circe.{Encoder, Json}
import io.circe.generic.auto._
import io.circe.syntax._
import org.slf4j.LoggerFactory
import sttp.client3._

import java.util.concurrent.TimeUnit

private[vault] final case class LeaseDTO(
    lease_id: Option[String],
    renewable: Boolean,
    lease_duration: Option[Long],
    data: Option[Map[String, Option[String]]]
)

abstract class SecretEngineProvider[Effect[_], ProviderContext, Config](implicit
    sync: Sync[Effect],
    clock: Clock[Effect]
) extends Provider[Effect, ProviderContext, Lease]
    with HttpSupport {
  protected val log = LoggerFactory.getLogger(getClass)

  /**
    * the path at which this engine is mounted
    * @return
    */
  def path: String

  /**
    * write a config for this engine path
    * @param config
    */
  def writeConfig(name: String, config: Config)(implicit encoder: Encoder[Config]): ReaderT[Effect, VaultConfig, Json] = for {
    response <-
      ReaderT[Effect, VaultConfig, Response[Either[String, String]]] { vaultConfig =>
        val request = basicRequest
          .post(uri"${vaultConfig.apiUrl}/$path/config/$name")
          .body(config.asJson.noSpaces)
          .header("X-Vault-Token", vaultConfig.token)
          .send(backend)

        sync.delay(request)
      }
    json <- ReaderT.liftF(response.parseJson)
  } yield json

  /** renew a lease
    *
    * https://www.vaultproject.io/api/system/leases.html#renew-lease
    *
    * @param lease
    *   a lease
    * @param increment
    *   time to extend the lease in seconds
    *   (depending on the engine, this might be treated as the new ttl of the lease, this is the case for Mongo Atlas)
    * @return
    *   reader for an extended lease
    */
  def renew(
      lease: Lease,
      increment: Option[Long]
  ): ReaderT[Effect, VaultConfig, Lease] = lease.leaseId
    .map { leaseId =>
      for {
        now <- ReaderT.liftF(clock.realTime(TimeUnit.SECONDS))
        response <- ReaderT[Effect, VaultConfig, Response[Either[String, String]]] { vaultConfig =>
          sync.delay {
            basicRequest
              .post(uri"${vaultConfig.apiUrl}/sys/leases/renew")
              .body(
                Json
                  .obj(
                    "lease_id" -> Json.fromString(leaseId),
                    "increment" -> Json.fromLong(increment.getOrElse(3600))
                  )
                  .noSpaces
              )
              .header("X-Vault-Token", vaultConfig.token)
              .send(backend)
          }
        }
        lease <- {
          ReaderT.liftF {
            log.debug("response from vault: {}", response)
            parseLease(response).map { renewed =>
              renewed.copy(
                data = lease.data,
                creationTime = lease.creationTime,
                lastRenewalTime = Some(now)
              )
            }
          }
        }
      } yield lease
    }
    .getOrElse(
      ReaderT[Effect, VaultConfig, Lease](_ =>
        sync.raiseError(new IllegalArgumentException("no lease id defined"))
      )
    )

  /** Refresh a lease reference based on the current time.
    *
    * This is not a continuous refresh, the flow is:
    *
    *   1. if lease is not renewable: create a new lease
    *   2. if its not expired but the current time is greater then issue time * refresh ratio:
    *      refresh the current lease
    *   3. return the current lease otherwise
    *
    * @param leaseRef
    *   a reference to the current (maybe empty) lease
    * @param increment
    *   the increment to request in the renew
    * @param refreshRatio
    *   the ratio of duration time that is allowed to pass before refreshing the
    *   lease
    * @param newLease
    *   the fallback effect reader for creating a new lease
    * @return
    *   an effect reader with the logic above applied
    */
  def refresh(leaseRef: Ref[Effect, Option[Lease]],
              refreshRatio: Double = 0.5)(
    create: ReaderT[Effect, VaultConfig, Lease],
    renew: Lease => ReaderT[Effect, VaultConfig, Lease],
    maxTtlSeconds: ReaderT[Effect, VaultConfig, Long] = ReaderT.liftF(sync.pure(3600)),
    maxReached: ReaderT[Effect, VaultConfig, Unit] = ReaderT.liftF(sync.unit)
  ): ReaderT[Effect, VaultConfig, Lease] = {
    for {
      now <- ReaderT.liftF(clock.realTime(TimeUnit.SECONDS))
      currentLease <- ReaderT.liftF(leaseRef.get)
      max <- maxTtlSeconds
      valid <- {
        currentLease match {
          case Some(lease) =>
            val duration = lease.leaseDuration.getOrElse(0L)
            val expiryTime = lease.lastRenewalTime.getOrElse(lease.creationTime) + duration
            val ratioTime = expiryTime - duration * refreshRatio
            val totalDuration = lease.totalLeaseDuration(now)

            if (!lease.renewable) {
              create
            } else if (totalDuration >= max) {
              maxReached.flatMapF(_ => sync.pure(lease))
            } else if (now >= ratioTime) {
              renew(lease)
            } else ReaderT[Effect, VaultConfig, Lease](_ => sync.pure(lease))

          case None => create
        }
      }
      updated <- ReaderT[Effect, VaultConfig, Lease](_ =>
        leaseRef.set(Some(valid)) *> sync.delay(valid)
      )
    } yield updated
  }

  protected def parseLease(
      response: Response[Either[String, String]]
  ): Effect[Lease] = {
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
      creationTime = now
    )
  }
}
