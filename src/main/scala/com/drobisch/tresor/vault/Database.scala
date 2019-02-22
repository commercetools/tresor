package com.drobisch.tresor.vault

import cats.effect.{ Clock, Sync }
import com.softwaremill.sttp._

final case class DatabaseContext(role: String, vaultConfig: VaultConfig)

/**
 * implementation of the vault Databases engine API
 *
 * https://www.vaultproject.io/api/secret/databases
 *
 * @tparam F context type to use
 */
class Database[F[_]](implicit sync: Sync[F], clock: Clock[F]) extends SecretEngineProvider[F, DatabaseContext] {
  /**
   * read the credentials for a DB role
   *
   * @param context database context with a role
   * @return vault lease
   */
  override def secret(context: DatabaseContext): F[Lease] = {
    val response = sttp
      .get(uri"${context.vaultConfig.apiUrl}/database/creds/${context.role}")
      .header("X-Vault-Token", context.vaultConfig.token)
      .send()

    log.debug("response from vault: {}", response)

    parseLease(response)
  }
}

object Database {
  def apply[F[_]](implicit sync: Sync[F], clock: Clock[F]) = new Database[F]
}
