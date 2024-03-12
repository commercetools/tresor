package com.commercetools.tresor

import io.circe.{Decoder, Json}

/** basic types to implement secrets coming from https://www.vaultproject.io
  */
package object vault {
  final case class VaultConfig(apiUrl: String, token: String)

  /** value type for a vault lease
    *
    * see https://www.vaultproject.io/docs/concepts/lease.html
    *
    * @param leaseId
    *   lease id
    * @param data
    *   secret data values associated to the lease
    * @param renewable
    *   true if the lease validation period can be extends
    * @param leaseDuration
    *   duration of the lease starting with creation
    * @param creationTime
    *   the time of creation of the lease
    * @param totalLeaseDuration
    *   the total time of the lease
    */
  final case class Lease(
      leaseId: Option[String],
      data: Option[Json],
      renewable: Boolean,
      leaseDuration: Option[Long],
      creationTime: Long,
      lastRenewalTime: Option[Long] = None
  ) {
    def totalLeaseDuration(now: Long): Long = now - creationTime

    def as[Data: Decoder]: Either[Throwable, Data] =
      data match {
        case Some(data) => data.as[Data]
        case None       => Left(new NoSuchElementException("no data"))
      }
  }
}
