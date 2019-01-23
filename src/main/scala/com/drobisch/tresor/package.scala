package com.drobisch

package object tresor {
  final case class DefaultSecret(data: Map[String, Option[String]])

  implicit object DefaultSecret extends Secret[DefaultSecret] {
    override def id(secret: DefaultSecret): Option[String] = None
    override def data(secret: DefaultSecret): Option[Map[String, Option[String]]] = Some(secret.data)
    override def renewable(secret: DefaultSecret): Boolean = false
    override def validDuration(secret: DefaultSecret): Option[Long] = None
  }
}
