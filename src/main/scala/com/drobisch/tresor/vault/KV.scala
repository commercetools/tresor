package com.drobisch.tresor.vault

import com.softwaremill.sttp._
import cats.effect.{ Clock, Sync }

final case class KeyValueContext(key: String)

/**
 * implementation of the vault KV engine API
 *
 * https://www.vaultproject.io/api/secret/kv/kv-v1.html
 *
 * @tparam F effect type to use
 */
class KV[F[_]](implicit sync: Sync[F], clock: Clock[F]) extends SecretEngineProvider[F, (KeyValueContext, VaultConfig)] {
  /**
   * read the secret from a path
   *
   * @param context key value context with key and vault config
   * @return non-renewable vault lease
   */
  def secret(context: (KeyValueContext, VaultConfig)): F[Lease] = {
    val (kv, vaultConfig) = context

    val response = sttp
      .get(uri"${vaultConfig.apiUrl}/secret/${kv.key}")
      .header("X-Vault-Token", vaultConfig.token)
      .send()

    log.debug("response from vault: {}", response)

    parseLease(response)
  }
}

object KV {
  def apply[F[_]](implicit sync: Sync[F], clock: Clock[F]) = new KV[F]
}