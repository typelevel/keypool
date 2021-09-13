package org.typelevel.keypool

/**
 * Reusable is a Coproduct of the two states a Resource can be in at the end of its lifetime.
 *
 * If it is Reuse then it will be attempted to place back in the pool, if it is in DontReuse the
 * resource will be shutdown.
 */
sealed trait Reusable
object Reusable {
  case object Reuse extends Reusable
  case object DontReuse extends Reusable
}
