package com.drobisch.tresor.vault

import com.softwaremill.sttp._
import cats.effect.{ Clock, Sync }

final case class KeyValueContext(key: String, vaultConfig: VaultConfig)

/**
 * implementation of the vault KV engine API
 *
 * https://www.vaultproject.io/api/secret/kv/kv-v1.html
 *
 * @tparam F effect type to use
 */
class KV[F[_]](implicit sync: Sync[F], clock: Clock[F]) extends SecretEngineProvider[F, KeyValueContext] {
  /**
   * read the secret from a path
   *
   * @param context key value context with key and vault config
   * @return non-renewable vault lease
   */
  def secret(context: KeyValueContext): F[Lease] = {
    val response = sttp
      .get(uri"${context.vaultConfig.apiUrl}/secret/${context.key}")
      .header("X-Vault-Token", context.vaultConfig.token)
      .send()

    log.debug("response from vault: {}", response)

    parseLease(response)
  }
}

object KV {
  def apply[F[_]](implicit sync: Sync[F], clock: Clock[F]) = new KV[F]
}