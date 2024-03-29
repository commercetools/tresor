package com.commercetools.tresor.vault

import cats.effect.Sync
import cats.implicits._
import io.circe._
import io.circe.syntax._
import sttp.client3._

trait LoginRequest

final case class GcpLoginRequest(role: String, jwt: String) extends LoginRequest
final case class GcpAuthMetadata(role: String)
final case class GcpAuth(
    client_token: String,
    metadata: GcpAuthMetadata,
    lease_duration: Long,
    renewable: Boolean
)
final case class GcpLoginResponse(auth: GcpAuth, data: Option[Json] = None)

/** mainly implemented with https://www.vaultproject.io/api-docs/auth/gcp#login
  * in mind, but should work with other methods too
  */
trait Authentication {
  def login[F[_], Input <: LoginRequest, Output](
      vaultUrl: String,
      authPath: String,
      request: Input
  )(implicit
      sync: Sync[F],
      encoder: Encoder[Input],
      decoder: Decoder[Output]
  ): F[Output]
}

object VaultAuthentication extends Authentication with HttpSupport {
  override def login[F[_], Input <: LoginRequest, Output](
      vaultUrl: String,
      authPath: String,
      request: Input
  )(implicit
      sync: Sync[F],
      encoder: Encoder[Input],
      decoder: Decoder[Output]
  ): F[Output] =
    for {
      response <- sync.delay(
        basicRequest
          .post(uri"$vaultUrl/$authPath/login")
          .body(request.asJson.noSpaces)
          .send(backend)
      )
      parsed <- response.parseJson.map(_.as[Output])
      result <- sync.fromEither(parsed)
    } yield result
}
