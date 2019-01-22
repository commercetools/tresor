package com.drobisch.tresor

package object vault {
  case class VaultConfig(apiUrl: String, token: String)
  case class Lease(data: Map[String, String], renewable: Boolean = false)

  object KV extends VaultKVProvider

  implicit object VaultSecretLease extends Secret[Lease] {
    override def data(secret: Lease): Map[String, String] = secret.data
    override def id(secret: Lease): Option[String] = None
    override def renewable(secret: Lease): Boolean = secret.renewable
  }
}
