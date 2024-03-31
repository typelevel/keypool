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

package org.typelevel.keypool.internal

import cats.Applicative
import cats.effect.kernel.Resource

private[keypool] trait Metrics[F[_]] {

  /**
   * Increments the number of idle resources.
   */
  def idleInc: F[Unit]

  /**
   * Decrements the number of idle resources.
   */
  def idleDec: F[Unit]

  /**
   * Records the number of in-use resources.
   */
  def inUseCount: Resource[F, Unit]

  /**
   * Records for how long the resource has been in use.
   */
  def inUseRecordDuration: Resource[F, Unit]

  /**
   * Increments the number of acquired resources.
   */
  def acquiredTotalInc: F[Unit]

  /**
   * Records how long does it take to acquire a resource.
   */
  def acquireRecordDuration: Resource[F, Unit]

}

private[keypool] object Metrics {

  trait Provider[F[_]] {
    def get: F[Metrics[F]]
  }

  object Provider {
    def noop[F[_]: Applicative]: Provider[F] =
      new Provider[F] {
        def get: F[Metrics[F]] = Applicative[F].pure(Metrics.noop)
      }
  }

  def noop[F[_]: Applicative]: Metrics[F] =
    new Metrics[F] {
      def idleInc: F[Unit] = Applicative[F].unit
      def idleDec: F[Unit] = Applicative[F].unit
      def inUseCount: Resource[F, Unit] = Resource.unit
      def inUseRecordDuration: Resource[F, Unit] = Resource.unit
      def acquiredTotalInc: F[Unit] = Applicative[F].unit
      def acquireRecordDuration: Resource[F, Unit] = Resource.unit
    }

}
