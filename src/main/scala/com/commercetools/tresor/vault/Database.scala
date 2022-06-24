package com.commercetools.tresor.vault

import cats.effect.{Clock, Sync}
import com.commercetools.tresor.Secret
import io.circe.Json
import sttp.client3._

final case class DatabaseContext(role: String)

/** implementation of the vault Databases engine API
  *
  * https://www.vaultproject.io/api/secret/databases
  *
  * @tparam F
  *   context type to use
  */
class Database[F[_]](val path: String)(implicit
    val sync: Sync[F],
    val clock: Clock[F]
) extends SecretEngineProvider[F, (DatabaseContext, VaultConfig), Json] {

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
      .get(uri"${vaultConfig.apiUrl}/$path/creds/${db.role}")
      .header("X-Vault-Token", vaultConfig.token)
      .send(backend)

    log.debug("response from vault: {}", response)

    parseLease(response)
  }
}

object Database {
  def apply[F[_]](path: String)(implicit sync: Sync[F], clock: Clock[F]) =
    new Database[F](path)(sync, clock)
}
