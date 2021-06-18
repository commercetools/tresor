package com.drobisch.tresor.vault

import cats.effect.{Clock, Sync}
import com.drobisch.tresor.Secret
import sttp.client3._

final case class DatabaseContext(role: String)

/** implementation of the vault Databases engine API
  *
  * https://www.vaultproject.io/api/secret/databases
  *
  * @tparam F
  *   context type to use
  */
class Database[F[_]](implicit sync: Sync[F], clock: Clock[F])
    extends SecretEngineProvider[F, (DatabaseContext, VaultConfig)] {

  /** read the credentials for a DB role
    *
    * @param context
    *   database context with a role
    * @return
    *   vault lease
    */
  override def secret(
      context: (DatabaseContext, VaultConfig)
  )(implicit secret: Secret[Lease]): F[Lease] = {
    val (db, vaultConfig) = context

    val response = basicRequest
      .get(uri"${vaultConfig.apiUrl}/database/creds/${db.role}")
      .header("X-Vault-Token", vaultConfig.token)
      .send(backend)

    log.debug("response from vault: {}", response)

    parseLease(response)
  }
}

object Database {
  def apply[F[_]](implicit sync: Sync[F], clock: Clock[F]) = new Database[F]
}
