package examples

object KVExample extends App {
  // #kv-example
  import cats.effect.{IO, Timer}
  import scala.concurrent.ExecutionContext
  import com.drobisch.tresor.vault._

  implicit val executionContext: ExecutionContext = ???
  implicit val timer: Timer[IO] = cats.effect.IO.timer(executionContext)

  val vaultConfig =
    VaultConfig(apiUrl = "http://vault-host:8200/v1", token = "vault-token")

  val kvSecret: IO[Lease] =
    KV[cats.effect.IO].secret(KeyValueContext(key = "treasure"), vaultConfig)
  // #kv-example
}
