package com.drobisch.tresor

package object vault {
  final case class VaultConfig(apiUrl: String, token: String)

  final case class Lease(
    leaseId: Option[String],
    data: Map[String, Option[String]],
    renewable: Boolean,
    leaseDuration: Option[Long])

  implicit object VaultSecretLease extends Secret[Lease] {
    override def data(secret: Lease): Option[Map[String, Option[String]]] = Some(secret.data)
    override def id(secret: Lease): Option[String] = secret.leaseId
    override def renewable(secret: Lease): Boolean = secret.renewable
    override def validDuration(secret: Lease): Option[Long] = secret.leaseDuration
  }
}
