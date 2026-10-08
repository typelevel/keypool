/*
 * Copyright (c) 2019 Typelevel
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.typelevel.keypool

import cats.Functor
import cats.effect.kernel._

/**
 * A managed Resource.
 *
 * This knows whether it was reused or not, and has a reference that when it leaves the controlling
 * scope will dictate whether it is shutdown or returned to the pool.
 *
 * @param value
 *   the underlying resource held by this `Managed` instance.
 * @param isReused
 *   indicates whether the resource was taken from the pool (`true`) or newly created (`false`).
 * @param canBeReused
 *   A mutable reference controlling reuse: when the `Managed` is released this `Ref` determines
 *   whether the resource is returned to the pool or shut down.
 *
 * @note
 *   If the caller does not modify `canBeReused` on the returned `Managed`, the pool's default reuse
 *   state (configured via [[KeyPool.Builder.withDefaultReuseState]]) will be used when the resource
 *   is released.
 *
 * @see
 *   [[Reusable]]
 */
final class Managed[F[_], A] private[keypool] (
    val value: A,
    val isReused: Boolean,
    val canBeReused: Ref[F, Reusable]
)

object Managed {
  implicit def managedFunctor[F[_]]: Functor[Managed[F, *]] = new Functor[Managed[F, *]] {
    def map[A, B](fa: Managed[F, A])(f: A => B): Managed[F, B] = new Managed[F, B](
      f(fa.value),
      fa.isReused,
      fa.canBeReused
    )
  }
}
