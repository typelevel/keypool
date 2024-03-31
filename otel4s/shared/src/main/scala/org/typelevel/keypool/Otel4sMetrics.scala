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
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.MeterProvider

object Otel4sMetrics {

  def provider[F[_]: Monad](
      meterProvider: MeterProvider[F],
      poolName: String
  ): Metrics.Provider[F] =
    new Metrics.Provider[F] {
      def get: F[Metrics[F]] =
        for {
          meter <- meterProvider.get("org.typelevel.keypool")

          idle <- meter
            .upDownCounter[Long]("idle.current")
            .withUnit("{resource}")
            .withDescription("A current number of idle resources")
            .create

          inUse <- meter
            .upDownCounter[Long]("in_use.current")
            .withUnit("{resource}")
            .withDescription("A current number of resources in use")
            .create

          inUseDuration <- meter
            .histogram[Long]("in_use.duration")
            .withUnit("ms")
            .withDescription("For how long a resource is in use")
            .create

          acquiredTotal <- meter
            .counter[Long]("acquired.total")
            .withUnit("{resource}")
            .withDescription("A total number of acquired resources")
            .create

          acquireDuration <- meter
            .histogram[Long]("acquire.duration")
            .withUnit("ms")
            .withDescription("How long does it take to acquire a resource")
            .create
        } yield new Metrics[F] {
          private val nameAttribute = Attribute("pool.name", poolName)

          def idleInc: F[Unit] =
            idle.inc(nameAttribute)

          def idleDec: F[Unit] =
            idle.dec(nameAttribute)

          def inUseCount: Resource[F, Unit] =
            Resource.make(inUse.inc(nameAttribute))(_ => inUse.dec(nameAttribute))

          def inUseRecordDuration: Resource[F, Unit] =
            inUseDuration.recordDuration(TimeUnit.MILLISECONDS, nameAttribute)

          def acquiredTotalInc: F[Unit] =
            acquiredTotal.inc(nameAttribute)

          def acquireRecordDuration: Resource[F, Unit] =
            acquireDuration.recordDuration(TimeUnit.MILLISECONDS, nameAttribute)
        }
    }
}
