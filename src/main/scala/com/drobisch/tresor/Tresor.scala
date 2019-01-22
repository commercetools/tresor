package com.drobisch.tresor

trait Secret[T] {
  def id(secret: T): Option[String]
  def data(secret: T): Map[String, String]
  def renewable(secret: T): Boolean
}

trait Provider[C[_], P, T] {
  def getSecret(key: String, providerContext: P): C[T]
}

object Tresor {
  def secret[C[_], P, T : Secret](key: String,
                                  providerContext: P)(provider: Provider[C, P, T]): C[T] = provider.getSecret(key, providerContext)
}