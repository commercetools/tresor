package com.drobisch.tresor

import scala.language.higherKinds

import cats.effect.Sync

trait Secret[T] {
  def id(secret: T): Option[String]
  def data(secret: T): Option[Map[String, Option[String]]]
  def renewable(secret: T): Boolean
  def validDuration(secret: T): Option[Long]
}

trait Provider[C[_], P, T] {
  def secret(providerContext: P)(implicit sync: Sync[C]): C[T]
}

object Tresor {
  def apply[C[_]: Sync, P, T: Secret](provider: Provider[C, P, T]) = provider
}