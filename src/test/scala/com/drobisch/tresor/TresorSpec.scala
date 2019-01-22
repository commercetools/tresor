package com.drobisch.tresor

import cats.effect.IO
import com.drobisch.tresor.vault.{ KeyValueContext, Lease }
import org.scalatest.{ FlatSpec, Matchers }

class TresorSpec extends FlatSpec with Matchers {

  object TestProvider extends Provider[IO, KeyValueContext, Lease] {
    override def getSecret(key: String, providerContext: KeyValueContext): IO[Lease] = IO.pure(Lease(Map("token" -> "value")))
  }

  "Tresor" should "get secret from provider" in {
    import com.drobisch.tresor.vault._

    val context = KeyValueContext(VaultConfig(apiUrl = "http://localhost:8200/v1", token = "vault-token"))

    val expectedLease = Lease(Map("token" -> "value"))

    Tresor.secret("secret", context)(provider = TestProvider).unsafeRunSync() should be(expectedLease)
  }
}
