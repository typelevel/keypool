/*
 * Copyright (c) 2024 Typelevel
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

import java.util.concurrent.TimeUnit

import cats.Monad
import cats.effect.kernel.Resource
import cats.syntax.functor._
import cats.syntax.flatMap._
import org.typelevel.keypool.internal.Metrics
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.{BucketBoundaries, Meter}

object Otel4sMetrics {

  private val DefaultHistogramBuckets: BucketBoundaries =
    BucketBoundaries(Vector(.005, .01, .025, .05, .075, .1, .25, .5, .75, 1, 2.5, 5, 7.5, 10))

  /**
   * Creates metrics provider using otel4s `Meter` under the hood.
   *
   * Use `attributes` to customize the metrics.
   *
   * @example
   *   {{{
   * val attributes = Attributes(Attribute("pool.name", "db-pool"))
   *
   * Otel4sMetrics.provider[IO](attributes = attributes)
   *   }}}
   *
   * @param prefix
   *   the prefix to prepend to the metrics
   *
   * @param attributes
   *   the attributes to attach to the measurements
   *
   * @param inUseDurationSecondsHistogramBuckets
   *   the histogram buckets for the 'in_use.duration' histogram
   *
   * @param acquireDurationSecondsHistogramBuckets
   *   the histogram buckets for 'acquire.duration' histogram
   */
  def provider[F[_]: Monad: Meter](
      prefix: String = "keypool",
      attributes: Attributes = Attributes.empty,
      inUseDurationSecondsHistogramBuckets: BucketBoundaries = DefaultHistogramBuckets,
      acquireDurationSecondsHistogramBuckets: BucketBoundaries = DefaultHistogramBuckets
  ): Metrics.Provider[F] =
    new Metrics.Provider[F] {
      def get: F[Metrics[F]] =
        for {
          idle <- Meter[F]
            .upDownCounter[Long](s"$prefix.idle.current")
            .withUnit("{resource}")
            .withDescription("A current number of idle resources.")
            .create

          inUse <- Meter[F]
            .upDownCounter[Long](s"$prefix.in_use.current")
            .withUnit("{resource}")
            .withDescription("A current number of resources in use.")
            .create

          inUseDuration <- Meter[F]
            .histogram[Long](s"$prefix.in_use.duration")
            .withUnit("s")
            .withDescription("For how long a resource is in use.")
            .withExplicitBucketBoundaries(inUseDurationSecondsHistogramBuckets)
            .create

          acquiredTotal <- Meter[F]
            .counter[Long](s"$prefix.acquired.total")
            .withUnit("{resource}")
            .withDescription("A total number of acquired resources.")
            .create

          acquireDuration <- Meter[F]
            .histogram[Long](s"$prefix.acquire.duration")
            .withUnit("s")
            .withDescription("How long does it take to acquire a resource.")
            .withExplicitBucketBoundaries(acquireDurationSecondsHistogramBuckets)
            .create
        } yield new Metrics[F] {
          def idleInc: F[Unit] =
            idle.inc(attributes)

          def idleDec: F[Unit] =
            idle.dec(attributes)

          def inUseCount: Resource[F, Unit] =
            Resource.make(inUse.inc(attributes))(_ => inUse.dec(attributes))

          def inUseRecordDuration: Resource[F, Unit] =
            inUseDuration.recordDuration(TimeUnit.SECONDS, attributes)

          def acquiredTotalInc: F[Unit] =
            acquiredTotal.inc(attributes)

          def acquireRecordDuration: Resource[F, Unit] =
            acquireDuration.recordDuration(TimeUnit.SECONDS, attributes)
        }
    }
}
