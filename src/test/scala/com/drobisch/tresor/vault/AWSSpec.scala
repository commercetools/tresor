package com.drobisch.tresor.vault

import cats.data.ReaderT

import java.util.concurrent.TimeUnit
import io.circe.generic.auto._
import io.circe.syntax._
import cats.effect.IO
import cats.effect.concurrent.Ref
import com.drobisch.tresor.{StepClock, WireMockSupport}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.capabilities
import sttp.client3._
import sttp.client3.testing.SttpBackendStub

class AWSSpec extends AnyFlatSpec with Matchers with WireMockSupport {

  "AWS provider" should "create credentials from vault AWS engine" in {
    import com.github.tomakehurst.wiremock.client.WireMock._

    val result: Lease = withWireMock(server =>
      IO.delay {
        val vaultCredentialsResponse = aResponse()
          .withStatus(200)
          .withBody(
            s"""{"request_id":"1","lease_id":"aws/creds/some-role/abcd-123456","renewable":true,"lease_duration":43200,"data":{"access_key":"key","secret_key":"secret","security_token":null},"wrap_info":null,"warnings":null,"auth":null}"""
          )

        val vaultCredentialsRequest =
          get(urlEqualTo("/v1/aws/creds/some-role?&ttl=3600s"))
            .withHeader("X-Vault-Token", equalTo("vault-token"))
            .willReturn(vaultCredentialsResponse)

        server.stubFor(vaultCredentialsRequest)
      }.flatMap { _ =>
        val vaultConfig = VaultConfig(
          apiUrl = s"http://localhost:${server.port()}/v1",
          token = "vault-token"
        )
        implicit val clock = StepClock(1)

        AWS[IO]("aws").secret(AwsContext(name = "some-role"), vaultConfig)
      }
    ).unsafeRunSync()

    val expectedLease = Lease(
      leaseId = Some("aws/creds/some-role/abcd-123456"),
      data = Map(
        "access_key" -> Some("key"),
        "secret_key" -> Some("secret"),
        "security_token" -> None
      ),
      renewable = true,
      leaseDuration = Some(43200),
      1
    )

    result should be(expectedLease)
  }

  it should "sts if requested" in {
    implicit val clock: StepClock = StepClock(1)

    val http = SttpBackendStub.synchronous
      .whenRequestMatchesPartial {
        case credentials
            if credentials.uri.path.mkString("/") == "v1/aws/sts/some-role" =>
          Response.ok[String](
            LeaseDTO(Some("sts-lease"), true, Some(60), None).asJson.noSpaces
          )
      }

    val mockedAws = new AWS[IO]("aws") {
      override protected implicit lazy val backend = http
    }

    val vaultConfig =
      VaultConfig(apiUrl = s"http://localhost/v1", token = "vault-token")

    mockedAws
      .secret(AwsContext(name = "some-role", useSts = true), vaultConfig)
      .unsafeRunSync() should be(
      Lease(
        leaseId = Some("sts-lease"),
        data = Map(),
        renewable = true,
        leaseDuration = Some(60),
        1
      )
    )
  }

  it should "renew lease" in {
    import com.github.tomakehurst.wiremock.client.WireMock._

    val result: Lease = withWireMock(server =>
      IO.delay {
        val vaultRenewResponse = aResponse()
          .withStatus(200)
          .withBody(
            s"""{"request_id":"1","lease_id":"aws/creds/some-role/abcd-123456","renewable":true,"lease_duration":60,"data":null,"wrap_info":null,"warnings":null,"auth":null}"""
          )

        val vaultRenewRequest = post(urlEqualTo("/v1/sys/leases/renew"))
          .withRequestBody(equalTo(s"""{"lease_id":"","increment":60}"""))
          .withHeader("X-Vault-Token", equalTo("vault-token"))
          .willReturn(vaultRenewResponse)

        server.stubFor(vaultRenewRequest)
      }.flatMap { _ =>
        val vaultConfig = VaultConfig(
          apiUrl = s"http://localhost:${server.port()}/v1",
          token = "vault-token"
        )
        val existingLease = Lease(
          leaseId = Some(""),
          renewable = true,
          data = Map("foo" -> Some("bar")),
          leaseDuration = Some(60),
          creationTime = 1
        )
        implicit val clock = StepClock(1)

        AWS[IO]("aws").renew(existingLease, increment = 60)(vaultConfig)
      }
    ).unsafeRunSync()

    val expectedLease = Lease(
      leaseId = Some("aws/creds/some-role/abcd-123456"),
      data = Map("foo" -> Some("bar")),
      renewable = true,
      leaseDuration = Some(60),
      creationTime = 1,
      lastRenewalTime = Some(1)
    )

    result should be(expectedLease)
  }

  it should "auto refresh lease" in {
    implicit val clock: StepClock = StepClock(1)

    val ttl = 60

    val aLease = Lease(
      leaseId = Some("init"),
      data = Map.empty,
      renewable = true,
      leaseDuration = Some(60),
      creationTime = 0
    )

    def leaseDTO(prefix: String) =
      LeaseDTO(
        Some(prefix + clock.realTime(TimeUnit.SECONDS).unsafeRunSync()),
        true,
        Some(60),
        None
      ).asJson.noSpaces

    val http = SttpBackendStub.synchronous
      .whenRequestMatchesPartial {
        case req if req.uri.path.mkString("/") == "v1/aws/creds/some-role" =>
          Response.ok[String](leaseDTO("new"))
        case req if req.uri.path.mkString("/") == "v1/sys/leases/renew" =>
          Response.ok[String](leaseDTO("renew"))
      }

    val mockedAws = new AWS[IO]("aws") {
      override protected implicit lazy val backend
          : SttpBackendStub[Identity, capabilities.WebSockets] = http
    }

    val vaultConfig =
      VaultConfig(apiUrl = s"http://localhost/v1", token = "vault-token")
    val awsContext = AwsContext(name = "some-role")
    val currentLease = Ref.unsafe[IO, Option[Lease]](Some(aLease))

    val leaseWithRefresh = mockedAws.refresh(currentLease, refreshTtl = ttl)(
      create = mockedAws.createCredentials(awsContext),
      renew = (lease, increment) =>
        if (lease.lastRenewalTime.forall(_ < ttl + 2))
          mockedAws.renew(lease, increment)
        else ReaderT.pure(lease.copy(leaseDuration = Some(0)))
    )(vaultConfig)

    leaseWithRefresh.unsafeRunSync() should be(
      Lease(
        Some("init"),
        Map(),
        renewable = true,
        Some(60),
        lastRenewalTime = None,
        creationTime = 0
      )
    )

    clock.timeRef.set(60).unsafeRunSync()

    val firstRenewed = Lease(
      Some("renew62"),
      Map(),
      renewable = true,
      leaseDuration = Some(60),
      creationTime = 0,
      lastRenewalTime = Some(61)
    )

    leaseWithRefresh.unsafeRunSync() should be(firstRenewed)
    clock.timeRef.set(62).unsafeRunSync()
    leaseWithRefresh.unsafeRunSync() should be(firstRenewed)

    clock.timeRef.set(93).unsafeRunSync()
    leaseWithRefresh.unsafeRunSync() should be(
      Lease(
        Some("renew95"),
        Map(),
        renewable = true,
        leaseDuration = Some(60),
        creationTime = 0,
        lastRenewalTime = Some(94)
      )
    )

    clock.timeRef.set(3600).attempt.unsafeRunSync()

    leaseWithRefresh.attempt.unsafeRunSync() match {
      case Left(_) => succeed
      case other   => fail(s"did not expect $other")
    }
  }
}
