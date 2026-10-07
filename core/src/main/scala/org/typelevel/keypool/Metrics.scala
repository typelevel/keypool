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

import cats.Applicative
import cats.effect.kernel.Resource

/** Pool metrics. Implementations must not fail pool operations. */
sealed trait Metrics[F[_]] {

  /** Tracks an acquisition until a resource is ready. */
  def acquire: Resource[F, Metrics.Acquisition[F]]

  /** Adds one idle resource. */
  def idleInc: F[Unit]

  /** Removes one idle resource. */
  def idleDec: F[Unit]

  /** Tracks a resource while it is in use. */
  def inUseCount: Resource[F, Unit]

  /** Records how long a resource is used. */
  def useDuration: Resource[F, Unit]

  /** Records how long it takes to create a new resource. */
  def createDuration: Resource[F, Unit]

  /** Records a resource removed permanently from the pool. */
  def resourceDestroyed(reason: Metrics.DestructionReason): F[Unit]

}

object Metrics {
  private[keypool] trait Unsealed[F[_]] extends Metrics[F]

  sealed trait DestructionReason extends Product with Serializable

  object DestructionReason {
    case object IdleTimeout extends DestructionReason
    case object MaxIdle extends DestructionReason
    case object MaxPerKey extends DestructionReason
    case object NotReusable extends DestructionReason
    case object PoolClosed extends DestructionReason
  }

  trait Acquisition[F[_]] {

    /** Marks the resource as ready. */
    def complete: F[Unit]

    /** Finishes an acquisition that did not complete. */
    private[keypool] def finish(exitCase: Resource.ExitCase): F[Unit]
  }

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
      def acquire: Resource[F, Acquisition[F]] =
        Resource.pure(new Acquisition[F] {
          def complete: F[Unit] = Applicative[F].unit
          private[keypool] def finish(exitCase: Resource.ExitCase): F[Unit] = Applicative[F].unit
        })
      def inUseCount: Resource[F, Unit] = Resource.unit
      def useDuration: Resource[F, Unit] = Resource.unit
      def createDuration: Resource[F, Unit] = Resource.unit
      def resourceDestroyed(reason: DestructionReason): F[Unit] = Applicative[F].unit
    }

}
