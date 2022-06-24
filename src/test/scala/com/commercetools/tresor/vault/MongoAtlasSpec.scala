package com.commercetools.tresor.vault

import cats.data.ReaderT
import cats.effect.{Clock, IO, Ref}
import com.commercetools.tresor.StepClock
import org.scalatest.Ignore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import scala.concurrent.duration.DurationInt
import cats.effect.unsafe.implicits.global

/** Atlas would need an account and elaborate setup these tests act as
  * documentation and setup for local development
  *
  * for functional/contract tests see DatabaseSpec and AWSSpec
  *
  * TODO: make this work with a local MongoDB
  */
@Ignore
class MongoAtlasSpec extends AnyFlatSpec with Matchers {
  val log = LoggerFactory.getLogger(getClass)

  val config =
    VaultConfig("http://0.0.0.0:8200/v1", "vault-plaintext-root-token")
  val role = sys.env.getOrElse("ATLAS_ROLE", "test-role")

  "Mongo Credentials" should "be created" in {
    implicit val clock = StepClock(1)

    Database[IO]("database/atlas")
      .secret(DatabaseContext(role), config)
      .unsafeRunSync()
  }

  it should "renew the lease" in {
    implicit val clock = StepClock(1)

    val atlasEngine = Database[IO]("database/atlas")

    val createAndRenew = for {
      initial <- atlasEngine.secret(DatabaseContext(role), config)
      _ = log.info(s"initial: $initial")
      renewed <- atlasEngine.renew(initial, 60)(config)
      _ = log.info(s"renewed: $renewed")
    } yield ()

    createAndRenew.unsafeRunSync()
  }

  it should "refresh the lease" in {
    implicit val clock = StepClock(1)

    val atlasEngine = Database[IO]("database/atlas")

    val createAndRenew = for {
      initialRef <- Ref.of[IO, Option[Lease]](None)

      renew = atlasEngine.refresh(initialRef, refreshTtl = 60)(
        create =
          ReaderT.liftF(atlasEngine.secret(DatabaseContext(role), config)),
        renew = atlasEngine.renew
      )(config)

      created <- renew
      _ = log.info(s"created: $created")
      _ <- clock.timeRef.set(15)
      notRenewed <- renew
      _ = log.info(s"not renewed: $notRenewed")
      _ <- clock.timeRef.set(302)
      renewed <- renew
      _ = log.info(s"renewed: $renewed")
      _ <- clock.timeRef.set(334)
      _ <- IO.sleep(2.seconds)
      _ <- renew
    } yield ()

    createAndRenew.unsafeRunSync()
  }

  it should "auto refresh" in {
    implicit val clock = Clock[IO]

    val atlasEngine = Database[IO]("database/atlas")

    val createAndRenew = for {
      initialRef <- ReaderT.liftF(Ref.of[IO, Option[Lease]](None))

      renew = atlasEngine.refresh(initialRef, refreshTtl = 15)(
        create =
          ReaderT.liftF(atlasEngine.secret(DatabaseContext(role), config)),
        renew = atlasEngine.renew
      )

      _ <- renew
      _ <- atlasEngine.autoRefresh(renew, every = 5.seconds)
    } yield ()

    createAndRenew(config).unsafeRunSync()
  }

}
