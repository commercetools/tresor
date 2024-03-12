package com.commercetools

package object tresor {

  /** a default secret type that only carries a value map and is not renewable
    *
    * @param data
    *   secret values
    */
  final case class DefaultSecret(data: Map[String, Option[String]])

  implicit object DefaultSecret
      extends Secret[DefaultSecret, Map[String, Option[String]]] {
    override def id(secret: DefaultSecret): Option[String] = None
    override def data(
        secret: DefaultSecret
    ): Option[Map[String, Option[String]]] = Some(secret.data)
    override def renewable(secret: DefaultSecret): Boolean = false
    override def validDuration(secret: DefaultSecret): Option[Long] = None
    override def creationTime(secret: DefaultSecret): Option[Long] = None
  }
}
