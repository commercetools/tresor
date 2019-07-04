package com.drobisch.tresor

import cats.effect._
import org.scalatest.{ FlatSpec, Matchers }

class TresorSpec extends FlatSpec with Matchers with WireMockSupport {
  case class TestContext(data: Map[String, Option[String]])

  class TestProvider[F[_]](implicit sync: Sync[F]) extends Provider[F, TestContext, DefaultSecret] {
    def secret(providerContext: TestContext)(implicit secret: Secret[DefaultSecret]): F[DefaultSecret] =
      sync.pure(DefaultSecret(providerContext.data))
  }

  "Tresor" should "get secret from test provider" in {
    val testContext = TestContext(Map("foo" -> Some("bar")))

    new TestProvider[IO].secret(testContext).unsafeRunSync() should be(DefaultSecret(testContext.data))
  }

}
