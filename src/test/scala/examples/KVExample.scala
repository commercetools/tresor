package examples

object KVExample extends App {
  // #kv-example
  import cats.effect.IO
  import scala.concurrent.ExecutionContext
  import com.commercetools.tresor.vault._
  import io.circe.syntax._

  implicit val executionContext: ExecutionContext = ???

  val vaultConfig =
    VaultConfig(apiUrl = "http://vault-host:8200/v1", token = "vault-token")

  val createOrUpdate: IO[KV2.Metadata] =
    KV2[cats.effect.IO](mountPath = "secret")
      .createOrUpdate(
        (KV2Context(secretPath = "treasure"), vaultConfig),
        Map("some" -> "data").asJson
      )

  val read: IO[Lease] =
    KV2[cats.effect.IO](mountPath = "secret")
      .secret(KV2Context(secretPath = "treasure"), vaultConfig)

  for {
    lease <- read
    secret <- IO.fromEither(lease.as[KV2.Secret[Map[String, String]]])
  } yield {
    println(secret.data) // print you data map
    println(secret.metadata.version) // print the secret metadata
  }

  // #kv-example
}
