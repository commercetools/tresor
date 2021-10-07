package com.drobisch.tresor

import cats.Applicative
import cats.effect.{Clock, IO, Ref}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

/** A simple clock which increases its time on every access of the realtime by
  * the given step size
  *
  * @param startTime
  *   the start time
  * @param stepSize
  *   the amount of time units to increase
  */
final case class StepClock(startTime: Long, stepSize: Int = 1)
    extends Clock[IO] {
  val timeRef: Ref[IO, Long] = Ref.unsafe(startTime)

  override def realTime: IO[FiniteDuration] = for {
    time <- timeRef.get
    next <- IO.pure(time + stepSize)
    _ <- timeRef.set(next)
  } yield FiniteDuration(time, TimeUnit.SECONDS)

  override def monotonic: IO[FiniteDuration] =
    IO.raiseError(new UnsupportedOperationException("only supports realtime"))

  override def applicative: Applicative[IO] = Applicative[IO]
}
