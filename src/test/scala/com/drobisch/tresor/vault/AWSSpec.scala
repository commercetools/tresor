package com.drobisch.tresor.vault

import java.util.concurrent.TimeUnit

import io.circe.generic.auto._
import io.circe.syntax._
import cats.effect.IO
import cats.effect.concurrent.Ref
import com.drobisch.tresor.{ StepClock, WireMockSupport }
import com.softwaremill.sttp.testing.SttpBackendStub
import com.softwaremill.sttp._
import org.scalatest.{ FlatSpec, Matchers }

class AWSSpec extends FlatSpec with Matchers with WireMockSupport {

  "AWS provider" should "create credentials from vault AWS engine" in {
    import com.github.tomakehurst.wiremock.client.WireMock._

    val result: Lease = withWireMock(server => IO.delay {
      val vaultCredentialsResponse = aResponse()
        .withStatus(200)
        .withBody(s"""{"request_id":"1","lease_id":"aws/creds/some-role/abcd-123456","renewable":true,"lease_duration":43200,"data":{"access_key":"key","secret_key":"secret","security_token":null},"wrap_info":null,"warnings":null,"auth":null}""")

      val vaultCredentialsRequest = get(urlEqualTo("/v1/aws/creds/some-role?&ttl=3600s"))
        .withHeader("X-Vault-Token", equalTo("vault-token"))
        .willReturn(vaultCredentialsResponse)

      server.stubFor(vaultCredentialsRequest)
    }.flatMap { _ =>
      val vaultConfig = VaultConfig(apiUrl = s"http://localhost:${server.port()}/v1", token = "vault-token")
      implicit val clock = StepClock(1)

      AWS[IO].secret(AwsContext(name = "some-role"), vaultConfig)
    }).unsafeRunSync()

    val expectedLease = Lease(
      leaseId = Some("aws/creds/some-role/abcd-123456"),
      data = Map("access_key" -> Some("key"), "secret_key" -> Some("secret"), "security_token" -> None),
      renewable = true,
      leaseDuration = Some(43200), 1)

    result should be(expectedLease)
  }

  it should "sts if requested" in {
    implicit val clock: StepClock = StepClock(1)

    val http = SttpBackendStub.synchronous
      .whenRequestMatchesPartial {
        case credentials if credentials.uri.path.mkString("/") == "v1/aws/sts/some-role" =>
          Response.ok[String](LeaseDTO(Some("sts-lease"), true, Some(60), None).asJson.noSpaces)
      }

    val mockedAws = new AWS[IO]() {
      override protected implicit val backend: SttpBackend[Id, Nothing] = http
    }

    val vaultConfig = VaultConfig(apiUrl = s"http://localhost/v1", token = "vault-token")

    mockedAws.secret(AwsContext(name = "some-role", useSts = true), vaultConfig).unsafeRunSync() should be(
      Lease(
        leaseId = Some("sts-lease"),
        data = Map(),
        renewable = true,
        leaseDuration = Some(60), 1))
  }

  it should "renew lease" in {
    import com.github.tomakehurst.wiremock.client.WireMock._

    val result: Lease = withWireMock(server => IO.delay {
      val vaultRenewResponse = aResponse()
        .withStatus(200)
        .withBody(s"""{"request_id":"1","lease_id":"aws/creds/some-role/abcd-123456","renewable":true,"lease_duration":60,"data":null,"wrap_info":null,"warnings":null,"auth":null}""")

      val vaultRenewRequest = post(urlEqualTo("/v1/sys/leases/renew"))
        .withRequestBody(equalTo(s"""{"lease_id":"","increment":60}"""))
        .withHeader("X-Vault-Token", equalTo("vault-token"))
        .willReturn(vaultRenewResponse)

      server.stubFor(vaultRenewRequest)
    }.flatMap { _ =>
      val vaultConfig = VaultConfig(apiUrl = s"http://localhost:${server.port()}/v1", token = "vault-token")
      val existingLease = Lease(leaseId = Some(""), renewable = true, data = Map.empty, leaseDuration = Some(60), issueTime = 1)
      implicit val clock = StepClock(1)

      AWS[IO].renew(existingLease, increment = Some(60))(vaultConfig)
    }).unsafeRunSync()

    val expectedLease = Lease(
      leaseId = Some("aws/creds/some-role/abcd-123456"),
      data = Map.empty,
      renewable = true,
      leaseDuration = Some(60),
      issueTime = 1)

    result should be(expectedLease)
  }

  it should "auto refresh lease" in {
    implicit val clock: StepClock = StepClock(1)

    val aLease = Lease(
      leaseId = Some("init"),
      data = Map.empty,
      renewable = true,
      leaseDuration = Some(60),
      issueTime = 0)

    def leaseDTO(prefix: String) =
      LeaseDTO(Some(prefix + clock.realTime(TimeUnit.SECONDS).unsafeRunSync()), true, Some(60), None).asJson.noSpaces

    val http = SttpBackendStub
      .synchronous
      .whenRequestMatchesPartial {
        case req if req.uri.path.mkString("/") == "v1/aws/creds/some-role" => Response.ok[String](leaseDTO("new"))
        case req if req.uri.path.mkString("/") == "v1/sys/leases/renew" => Response.ok[String](leaseDTO("renew"))
      }

    val mockedAws = new AWS[IO]() {
      override protected implicit val backend: SttpBackend[Id, Nothing] = http
    }

    val vaultConfig = VaultConfig(apiUrl = s"http://localhost/v1", token = "vault-token")
    val awsContext = AwsContext(name = "some-role")
    val currentLease = Ref.unsafe[IO, Option[Lease]](Some(aLease))

    val leaseWithRefresh = mockedAws.autoRefresh(currentLease)(mockedAws.createCredentials(awsContext))(vaultConfig)

    leaseWithRefresh.unsafeRunSync() should be(Lease(Some("init"), Map(), renewable = true, Some(60), 0))

    clock.timeRef.set(60).unsafeRunSync()

    leaseWithRefresh.unsafeRunSync() should be(Lease(Some("new61"), Map(), renewable = true, Some(60), 62))

    clock.timeRef.set(62).unsafeRunSync()

    leaseWithRefresh.unsafeRunSync() should be(Lease(Some("new61"), Map(), renewable = true, Some(60), 62))

    clock.timeRef.set(92).unsafeRunSync() // halflife

    leaseWithRefresh.unsafeRunSync() should be(Lease(Some("renew93"), Map(), renewable = true, Some(60), 94))

    clock.timeRef.set(600).unsafeRunSync() // expired

    leaseWithRefresh.unsafeRunSync() should be(Lease(Some("new601"), Map(), renewable = true, Some(60), 602))
  }
}
