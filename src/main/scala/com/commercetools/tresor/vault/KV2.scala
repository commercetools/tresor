package com.commercetools.tresor.vault

import cats.MonadError
import cats.effect.{Clock, Sync}
import com.commercetools.tresor.vault.KV2.MetadataResponse
import io.circe.generic.semiauto.deriveDecoder
import io.circe.syntax._
import io.circe.{Decoder, Json}
import sttp.client3._

final case class KV2Context(secretPath: String, version: Option[Long] = None)

/** implementation of the vault KV v2 engine API
  *
  * https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2
  *
  * @tparam F
  *   effect type to use
  */
class KV2[F[_]](val mountPath: String)(implicit
    val sync: Sync[F],
    val clock: Clock[F],
    val monadError: MonadError[F, Throwable]
) extends SecretEngineProvider[F, (KV2Context, VaultConfig), Nothing] {

  /** read the secret from a path
    *
    * @param context
    *   key value context with key and vault config
    * @return
    *   non-renewable vault lease
    */
  def secret(
      context: (KV2Context, VaultConfig)
  ): F[Lease] = {
    val (kv, vaultConfig) = context

    val versionPart = kv.version.map("?version=" + _).getOrElse("")

    val response = basicRequest
      .get(
        uri"${vaultConfig.apiUrl}/$mountPath/data/${kv.secretPath}$versionPart"
      )
      .header("X-Vault-Token", vaultConfig.token)
      .send(backend)

    log.debug("response from vault: {}", response)

    parseLease(response)
  }

  /** creates or updates the secret in the path
    *
    * @param context
    *   key value context with key and vault config
    * @param data
    *   the data to be stored
    */
  def createOrUpdate(
      context: (KV2Context, VaultConfig),
      data: Json
  ): F[KV2.Metadata] = {
    val (kv, vaultConfig) = context

    val casPart =
      kv.version.map(version => Json.obj("cas" -> Json.fromLong(version)))

    val response = basicRequest
      .post(uri"${vaultConfig.apiUrl}/$mountPath/data/${kv.secretPath}")
      .body(
        Json
          .obj(
            "data" -> data.asJson,
            "options" -> casPart.getOrElse(Json.Null)
          )
          .noSpaces
      )
      .header("X-Vault-Token", vaultConfig.token)
      .send(backend)

    log.debug("response from vault: {}", response)
    sync.map(parseBody[MetadataResponse](response))(_.data)
  }
}

object KV2 {
  final case class Metadata(
      created_time: String,
      deletion_time: String,
      destroyed: Boolean,
      version: Long,
      custom_metadata: Option[Json]
  )

  final case class MetadataResponse(data: Metadata)

  final case class Secret[Data](data: Data, metadata: Metadata)

  lazy implicit val metadataDecoder: Decoder[Metadata] =
    deriveDecoder[Metadata]

  lazy implicit val metadataResponseDecoder: Decoder[MetadataResponse] =
    deriveDecoder[MetadataResponse]

  implicit def secretDecoder[Data: Decoder]: Decoder[Secret[Data]] =
    deriveDecoder[KV2.Secret[Data]]

  def apply[F[_]](mountPath: String)(implicit
      sync: Sync[F],
      clock: Clock[F],
      monadError: MonadError[F, Throwable]
  ) =
    new KV2[F](mountPath)(sync, clock, monadError)
}
