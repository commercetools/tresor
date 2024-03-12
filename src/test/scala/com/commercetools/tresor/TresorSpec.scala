package com.commercetools.tresor

import cats.effect._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.unsafe.implicits.global

class TresorSpec extends AnyFlatSpec with Matchers with WireMockSupport {
  case class TestContext(data: Map[String, Option[String]])

  class TestProvider[F[_]](implicit sync: Sync[F])
      extends Provider[F, TestContext, DefaultSecret] {
    override def secret(context: TestContext): F[DefaultSecret] =
      sync.pure(DefaultSecret(context.data))

  }

  "Tresor" should "get secret from test provider" in {
    val testContext = TestContext(Map("foo" -> Some("bar")))

    new TestProvider[IO].secret(testContext).unsafeRunSync() should be(
      DefaultSecret(testContext.data)
    )
  }

}
