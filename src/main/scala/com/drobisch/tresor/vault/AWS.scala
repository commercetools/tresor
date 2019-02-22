package com.drobisch.tresor.vault

import cats.effect.{ Clock, Sync }
import com.softwaremill.sttp._

final case class AwsContext(
  name: String,
  roleArn: Option[String] = None,
  ttlString: Option[String] = None,
  useSts: Boolean = false,
  vaultConfig: VaultConfig)

/**
 * implementation of the vault AWS engine API
 *
 * https://www.vaultproject.io/api/secret/aws/index.html
 *
 * @tparam F effect type to use
 */
class AWS[F[_]](implicit sync: Sync[F], clock: Clock[F]) extends SecretEngineProvider[F, AwsContext] {

  /**
   * create a aws engine credential
   *
   * https://www.vaultproject.io/api/secret/aws/index.html#generate-credentials
   *
   * @param awsContext context
   * @return a new lease for aws resources
   */
  def createCredentials(awsContext: AwsContext): F[Lease] = sync.flatMap(sync.delay {
    val roleArnPart = awsContext.roleArn.map(s"&role_arn=" + _).getOrElse("")
    val ttlPart = "&ttl=" + awsContext.ttlString.getOrElse("3600s")
    val infixPart = if (awsContext.useSts) "sts" else "creds"

    val requestUri = s"${awsContext.vaultConfig.apiUrl}/aws/$infixPart/${awsContext.name}?$roleArnPart$ttlPart"

    sttp
      .get(uri"$requestUri")
      .header("X-Vault-Token", awsContext.vaultConfig.token)
      .send()
  }) { response =>
    log.debug("response from vault: {}", response)
    parseLease(response)
  }

  override def secret(context: AwsContext): F[Lease] = createCredentials(context)
}

object AWS {
  def apply[F[_]](implicit sync: Sync[F], clock: Clock[F]) = new AWS[F]
}
