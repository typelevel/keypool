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

import cats.Monad
import cats.syntax.functor._
import cats.syntax.flatMap._
import org.typelevel.otel4s.metrics._

private[keypool] trait Metrics[F[_]] {

  /**
   * The '''current''' number of the '''idle''' resources
   */
  def idle: UpDownCounter[F, Long]

  /**
   * The '''current''' number of the '''borrowed (in use)''' resources
   */
  def inUse: UpDownCounter[F, Long]

  def inUseDuration: Histogram[F, Double]

  /**
   * The '''total''' number of the '''acquired''' resources.
   *
   * The value can be only incremented.
   */
  def acquiredTotal: Counter[F, Long]

  def acquireDuration: Histogram[F, Double]

}

private[keypool] object Metrics {

  def fromMeterProvider[F[_]: Monad](meterProvider: MeterProvider[F]): F[Metrics[F]] = {
    for {
      meter <- meterProvider.get("org.typelevel.keypool")

      idleCounter <- meter
        .upDownCounter("idle")
        .withDescription("A current number of idle resources")
        .create

      inUseCounter <- meter
        .upDownCounter("in_use")
        .withDescription("A current number of resources in use")
        .create

      inUseDurationHistogram <- meter
        .histogram("in_use_duration")
        .withUnit("ms")
        .withDescription("For how long a resource is in use")
        .create

      acquiredCounter <- meter
        .counter("acquired_total")
        .withDescription("A total number of acquired resources")
        .create

      acquireDurationHistogram <- meter
        .histogram("acquire_duration")
        .withUnit("ms")
        .withDescription("How long does it take to acquire a resource")
        .create

    } yield new Metrics[F] {
      def idle: UpDownCounter[F, Long] = idleCounter
      def inUse: UpDownCounter[F, Long] = inUseCounter
      def inUseDuration: Histogram[F, Double] = inUseDurationHistogram
      def acquiredTotal: Counter[F, Long] = acquiredCounter
      def acquireDuration: Histogram[F, Double] = acquireDurationHistogram
    }
  }

}
