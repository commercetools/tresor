package com.drobisch.tresor

import cats.effect.{Clock, IO}
import cats.effect.concurrent.Ref

import scala.concurrent.duration.TimeUnit

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

  override def realTime(unit: TimeUnit): IO[Long] = for {
    time <- timeRef.get
    next <- IO.pure(time + stepSize)
    _ <- timeRef.set(next)
  } yield time

  override def monotonic(unit: TimeUnit): IO[Long] =
    IO.raiseError(new UnsupportedOperationException("only supports realtime"))
}
