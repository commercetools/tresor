package com.commercetools.tresor

/** Type class for secrets that are potentially only valid for a limited time
  * and which might be renewable
  *
  * @tparam T
  *   secret value type
  * @tparam D
  *   data value type
  */
trait Secret[T, D] {
  def id(secret: T): Option[String]
  def data(secret: T): Option[D]
  def renewable(secret: T): Boolean
  def validDuration(secret: T): Option[Long]
  def creationTime(secret: T): Option[Long]
}

/** Interface for secret providers. Other operations (creating secrets, renewing
  * etc.) are considered details of the provider.
  *
  * @tparam C
  *   context type of the secret
  * @tparam P
  *   provider context type (the input for the provider)
  * @tparam T
  *   provider result type (the output of the provider)
  */
trait Provider[C[_], P, T] {
  def secret(context: P): C[T]
}
