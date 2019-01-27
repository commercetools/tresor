package com.drobisch.tresor

import scala.language.higherKinds

import cats.effect.Sync

/**
 * Type class for secrets that are potentially only valid for a limited time
 * and which might be renewable
 *
 * @tparam T secret value type
 */
trait Secret[T] {
  def id(secret: T): Option[String]
  def data(secret: T): Option[Map[String, Option[String]]]
  def renewable(secret: T): Boolean
  def validDuration(secret: T): Option[Long]
}

/**
 * Marker interface for secret providers.
 * Operations (creating secrets, renewing etc.) are
 * considered details of the provider.
 *
 *
 * @tparam C context type of the secret
 * @tparam P provider context type (the input for the provider)
 * @tparam T provider result type (the output of the provider)
 */
trait Provider[S <: Provider[S, C, P, T], C[_], P, T]

object Tresor {
  /**
   * @param provider to use
   * @tparam S self type of the provider
   * @tparam C context type
   * @tparam P provider context type (the input for the provider)
   * @tparam T provider result type (the output of the provider)
   * @return the provider type with all types inferred
   */
  def apply[C[_]: Sync, P, T: Secret, S <: Provider[S, C, P, T]](provider: Provider[S, C, P, T]): S = provider.asInstanceOf[S]
}