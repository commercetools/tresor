package com.commercetools.tresor.vault

import cats.MonadError
import sttp.client3._
import io.circe.syntax._
import cats.effect.{Clock, Sync}

final case class KVContext(secretPath: String)

/** implementation of the vault KV v1 engine API
  *
  * https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v1
  *
  * @tparam F
  *   effect type to use
  */
class KV[F[_]](val mountPath: String)(implicit
    val sync: Sync[F],
    val clock: Clock[F],
    val monadError: MonadError[F, Throwable]
) extends SecretEngineProvider[F, (KVContext, VaultConfig), Nothing] {

  /** read the secret from a path
    *
    * @param context
    *   key value context with key and vault config
    * @return
    *   non-renewable vault lease
    */
  def secret(
      context: (KVContext, VaultConfig)
  ): F[Lease] = {
    val (kv, vaultConfig) = context

    val response = basicRequest
      .get(uri"${vaultConfig.apiUrl}/$mountPath/${kv.secretPath}")
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
      context: (KVContext, VaultConfig),
      data: Map[String, Option[String]]
  ): F[Unit] = {
    val (kv, vaultConfig) = context

    val response = basicRequest
      .post(uri"${vaultConfig.apiUrl}/$mountPath/${kv.secretPath}")
      .body(data.asJson.noSpaces)
      .header("X-Vault-Token", vaultConfig.token)
      .send(backend)

    log.debug("response from vault: {}", response)

    parseEmptyResponse(response)
  }
}

object KV {
  def apply[F[_]](mountPath: String)(implicit
      sync: Sync[F],
      clock: Clock[F],
      monadError: MonadError[F, Throwable]
  ) =
    new KV[F](mountPath)(sync, clock, monadError)
}
