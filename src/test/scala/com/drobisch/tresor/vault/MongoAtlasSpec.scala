package com.drobisch.tresor.vault

import cats.effect.IO
import com.drobisch.tresor.StepClock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

class MongoAtlasSpec extends AnyFlatSpec with Matchers {
  val log = LoggerFactory.getLogger(getClass)

  implicit val clock = StepClock(1)
  implicit val timer = cats.effect.IO.timer(scala.concurrent.ExecutionContext.global)

  val config = VaultConfig("http://0.0.0.0:8200/v1", "vault-plaintext-root-token")

  "Mongo Credentials" should "be created" in {
    Database[IO]("database/atlas")
      .secret(DatabaseContext("test-role"), config)
      .unsafeRunSync()
  }

  it should "renew the lease" in {
    val atlasEngine = Database[IO]("database/atlas")
    val createAndRenew = for {
      initial <- atlasEngine.secret(DatabaseContext("test-role"), config)
      _ =  log.info(s"initial: $initial")
      renewed <- atlasEngine.renew(initial, Some(15))(config)
      _ =  log.info(s"renewed: $renewed")
    } yield ()

    createAndRenew.unsafeRunSync()
  }

}
