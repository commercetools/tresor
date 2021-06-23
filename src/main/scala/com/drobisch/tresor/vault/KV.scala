package com.drobisch.tresor.vault

import sttp.client3._
import cats.effect.{Clock, Sync}
import com.drobisch.tresor.Secret

final case class KeyValueContext(key: String)

/** implementation of the vault KV engine API
  *
  * https://www.vaultproject.io/api/secret/kv/kv-v1.html
  *
  * @tparam F
  *   effect type to use
  */
class KV[F[_]](val path: String)(implicit sync: Sync[F], clock: Clock[F])
    extends SecretEngineProvider[F, (KeyValueContext, VaultConfig), Nothing] {

  /** read the secret from a path
    *
    * @param context
    *   key value context with key and vault config
    * @return
    *   non-renewable vault lease
    */
  def secret(
      context: (KeyValueContext, VaultConfig)
  )(implicit secret: Secret[Lease]): F[Lease] = {
    val (kv, vaultConfig) = context

    val response = basicRequest
      .get(uri"${vaultConfig.apiUrl}/$path/${kv.key}")
      .header("X-Vault-Token", vaultConfig.token)
      .send(backend)

    log.debug("response from vault: {}", response)

    parseLease(response)
  }
}

object KV {
  def apply[F[_]](path: String)(implicit sync: Sync[F], clock: Clock[F]) = new KV[F](path)
}
