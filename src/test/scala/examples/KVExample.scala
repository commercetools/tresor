package examples

object KVExample extends App {
  // #kv-example
  import cats.effect.IO
  import scala.concurrent.ExecutionContext
  import com.commercetools.tresor.vault._

  implicit val executionContext: ExecutionContext = ???

  val vaultConfig =
    VaultConfig(apiUrl = "http://vault-host:8200/v1", token = "vault-token")

  val kvSecret: IO[Lease] =
    KV[cats.effect.IO]("secret")
      .secret(KeyValueContext(key = "treasure"), vaultConfig)
  // #kv-example
}
