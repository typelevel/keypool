package io.chrisdavenport.keypool

import cats.effect.concurrent.Ref

/**
 * A managed Resource.
 *
 * This knows whether it was reused or not, and
 * has a reference that when it leaves the controlling
 * scope will dictate whether it is shutdown or returned
 * to the pool.
 */
final class Managed[F[_], Rezource] private[keypool] (
  val resource: Rezource,
  val isReused: Boolean,
  val canBeReused: Ref[F, Reusable]
)