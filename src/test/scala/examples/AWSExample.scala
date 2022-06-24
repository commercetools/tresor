package examples

import cats.effect.Ref

object AWSExample {
  // #aws-example
  import cats.effect.IO

  import com.commercetools.tresor.vault._

  implicit val executionContext: scala.concurrent.ExecutionContext = ???

  val vaultConfig =
    VaultConfig(apiUrl = s"http://vault-host/v1", token = "vault-token")
  val awsContext = AwsContext(name = "some-role")
  val initialLease: Ref[IO, Option[Lease]] = Ref.unsafe[IO, Option[Lease]](None)
  val awsEngine = AWS[IO]("aws")

  val leaseWithRefresh: IO[Lease] = AWS[IO]("aws").refresh(initialLease)(
    create = awsEngine.createCredentials(awsContext),
    renew = awsEngine.renew
  )(vaultConfig)
  // #aws-example
}
