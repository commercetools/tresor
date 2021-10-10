package com.drobisch.tresor.vault

import cats.data.ReaderT
import cats.effect.{Clock, Sync}
import com.drobisch.tresor.Secret
import sttp.client3._

final case class AwsContext(
    name: String,
    roleArn: Option[String] = None,
    ttlString: Option[String] = None,
    useSts: Boolean = false
)

/** implementation of the vault AWS engine API
  *
  * https://www.vaultproject.io/api/secret/aws/index.html
  *
  * @tparam F
  *   effect type to use
  */
class AWS[F[_]](val path: String)(implicit
    val sync: Sync[F],
    val clock: Clock[F]
) extends SecretEngineProvider[F, (AwsContext, VaultConfig), Nothing] {

  /** create a aws engine credential
    *
    * https://www.vaultproject.io/api/secret/aws/index.html#generate-credentials
    *
    * @param awsContext
    *   context
    * @return
    *   a new lease for aws resources
    */
  def createCredentials(
      awsContext: AwsContext
  ): ReaderT[F, VaultConfig, Lease] = ReaderT(vaultConfig =>
    sync.flatMap(sync.delay {
      val roleArnPart = awsContext.roleArn.map(s"&role_arn=" + _).getOrElse("")
      val ttlPart = "&ttl=" + awsContext.ttlString.getOrElse("3600s")
      val stsOrCreds = if (awsContext.useSts) "sts" else "creds"

      val requestUri =
        s"${vaultConfig.apiUrl}/$path/$stsOrCreds/${awsContext.name}?$roleArnPart$ttlPart"

      basicRequest
        .get(uri"$requestUri")
        .header("X-Vault-Token", vaultConfig.token)
        .send(backend)
    }) { response =>
      log.debug("response from vault: {}", response)
      parseLease(response)
    }
  )

  override def secret(
      secretContext: (AwsContext, VaultConfig)
  )(implicit secret: Secret[Lease]): F[Lease] = secretContext match {
    case (awsContext, vaultConfig) => createCredentials(awsContext)(vaultConfig)
  }
}

object AWS {
  def apply[F[_]](path: String)(implicit sync: Sync[F], clock: Clock[F]) =
    new AWS[F](path)(sync, clock)
}
