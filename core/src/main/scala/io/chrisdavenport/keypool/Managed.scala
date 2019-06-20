package io.chrisdavenport.keypool

import cats.Functor
import cats.effect.concurrent.Ref

/**
 * A managed Resource.
 *
 * This knows whether it was reused or not, and
 * has a reference that when it leaves the controlling
 * scope will dictate whether it is shutdown or returned
 * to the pool.
 */
final class Managed[F[_], A] private[keypool] (
  val value: A,
  val isReused: Boolean,
  val canBeReused: Ref[F, Reusable]
)

object Managed {
  implicit def managedFunctor[F[_]]: Functor[Managed[F, ?]] = new Functor[Managed[F, ?]]{
    def map[A, B](fa: Managed[F,A])(f: A => B): Managed[F,B] = new Managed[F, B](
      f(fa.value),
      fa.isReused,
      fa.canBeReused
    )
  }
}