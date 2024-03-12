package com.commercetools.tresor.vault

import cats.MonadError
import cats.data.ReaderT
import cats.effect.{Async, Clock, Ref, Sync}
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.commercetools.tresor.Provider
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.auto._
import io.circe.syntax._
import org.slf4j.{Logger, LoggerFactory}
import sttp.client3._

import scala.concurrent.duration.FiniteDuration

private[vault] final case class LeaseDTO(
    lease_id: Option[String],
    renewable: Boolean,
    lease_duration: Option[Long],
    data: Option[Json]
)

trait SecretEngineProvider[Effect[_], ProviderContext, Config]
    extends Provider[Effect, ProviderContext, Lease]
    with HttpSupport {
  implicit def sync: Sync[Effect]
  implicit def monadError: MonadError[Effect, Throwable]
  implicit def clock: Clock[Effect]
  protected val log: Logger = LoggerFactory.getLogger(getClass)

  /** the path at which this engine is mounted
    * @return
    */
  def mountPath: String

  /** write a config for this engine path
    * @param config
    */
  def writeConfig(name: String, config: Config)(implicit
      encoder: Encoder[Config]
  ): ReaderT[Effect, VaultConfig, Json] = for {
    response <-
      ReaderT[Effect, VaultConfig, Response[Either[String, String]]] {
        vaultConfig =>
          val request = basicRequest
            .post(uri"${vaultConfig.apiUrl}/$mountPath/config/$name")
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
    *   time to extend the lease in seconds (depending on the engine, this might
    *   be treated as the new ttl of the lease, this is the case for Mongo
    *   Atlas)
    * @return
    *   reader for an extended lease
    */
  def renew(
      lease: Lease,
      increment: Long
  ): ReaderT[Effect, VaultConfig, Lease] = lease.leaseId
    .map { leaseId =>
      for {
        now <- ReaderT.liftF(clock.realTime.map(_.toSeconds))
        response <- ReaderT[Effect, VaultConfig, Response[
          Either[String, String]
        ]] { vaultConfig =>
          sync.delay {
            basicRequest
              .post(uri"${vaultConfig.apiUrl}/sys/leases/renew")
              .body(
                Json
                  .obj(
                    "lease_id" -> Json.fromString(leaseId),
                    "increment" -> Json.fromLong(increment)
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
    * A. if lease is not renewable: create a new lease
    *
    * B. if its not expired but the current time is greater then issue time *
    * refresh ratio: refresh the current lease
    *
    * C. return the current lease otherwise
    *
    * @param leaseRef
    *   a reference to the current (maybe empty) lease
    * @param refreshTtl
    *   the increment to request in the renew
    * @param refreshRatio
    *   the ratio of duration time that is allowed to pass before refreshing the
    *   lease
    * @param newLease
    *   the fallback effect reader for creating a new lease
    * @return
    *   an effect reader with the logic above applied
    */
  def refresh(
      leaseRef: Ref[Effect, Option[Lease]],
      refreshTtl: Long = 3600,
      refreshRatio: Double = 0.5
  )(
      create: ReaderT[Effect, VaultConfig, Lease],
      renew: (Lease, Long) => ReaderT[Effect, VaultConfig, Lease],
      maxReached: Lease => Effect[Unit] = lease =>
        sync.raiseError(
          new RuntimeException(s"lease has reached max lifetime: $lease")
        )
  ): ReaderT[Effect, VaultConfig, Lease] = {
    for {
      now <- ReaderT.liftF(clock.realTime.map(_.toSeconds))
      currentLease <- ReaderT.liftF(leaseRef.get)
      valid <- {
        currentLease match {
          case Some(lease) =>
            val duration = lease.leaseDuration.getOrElse(0L)
            val expiryTime =
              lease.lastRenewalTime.getOrElse(lease.creationTime) + duration
            val refreshTime = expiryTime - duration * refreshRatio

            if (!lease.renewable) {
              create
            } else if (now >= refreshTime) {
              renew(lease, refreshTtl).flatMap(newLease => {
                if (newLease.leaseDuration.forall(_ < refreshTtl))
                  ReaderT.liftF(maxReached(newLease).map(_ => newLease))
                else ReaderT.pure(newLease)
              })
            } else ReaderT[Effect, VaultConfig, Lease](_ => sync.pure(lease))

          case None => create
        }
      }
      updated <- ReaderT[Effect, VaultConfig, Lease](_ =>
        leaseRef.set(Some(valid)) *> sync.delay(valid)
      )
    } yield updated
  }

  def autoRefresh(
      refresh: ReaderT[Effect, VaultConfig, Lease],
      every: FiniteDuration
  )(implicit timer: Async[Effect]): ReaderT[Effect, VaultConfig, Unit] = for {
    _ <- ReaderT.liftF(sync.delay(log.debug(s"auto refreshing every $every")))
    currentLease <- refresh
    _ <- ReaderT.liftF(
      sync.delay(log.debug(s"current lease during refresh: $currentLease"))
    )
    _ <- ReaderT.liftF(timer.sleep(every))
    _ <- autoRefresh(refresh, every)
  } yield ()

  protected def parseLease(
      response: Response[Either[String, String]]
  ): Effect[Lease] = {
    for {
      now <- clock.realTime.map(_.toSeconds)
      body <- sync.fromEither(response.body.left.map(new RuntimeException(_)))
      parsed <- monadError.fromEither(io.circe.parser.decode[LeaseDTO](body))
    } yield {
      val withTime = fromDto(parsed, now)
      log.debug("lease: {}", withTime)
      withTime
    }
  }

  protected def parseEmptyResponse(
      response: Response[Either[String, String]]
  ): Effect[Unit] = {
    for {
      _ <- sync.fromEither(response.body.left.map(new RuntimeException(_)))
    } yield ()
  }

  protected def parseBody[T: Decoder](
      response: Response[Either[String, String]]
  ): Effect[T] = {
    for {
      body <- sync.fromEither(response.body.left.map(new RuntimeException(_)))
      parsed <- sync.fromEither(io.circe.parser.decode[T](body))
    } yield parsed
  }

  private def fromDto(dto: LeaseDTO, now: Long): Lease =
    Lease(
      leaseId = dto.lease_id,
      data = dto.data,
      renewable = dto.renewable,
      leaseDuration = dto.lease_duration,
      creationTime = now
    )
}
