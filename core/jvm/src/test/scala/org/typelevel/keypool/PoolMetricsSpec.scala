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

import cats.effect._
import cats.effect.testkit._
import io.opentelemetry.sdk.metrics.{
  Aggregation,
  InstrumentSelector,
  InstrumentType,
  SdkMeterProviderBuilder,
  View
}
import munit.CatsEffectSuite
import org.typelevel.otel4s.Otel4s
import org.typelevel.otel4s.java.OtelJava
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.testkit.{HistogramPointData, MetricData, Sdk}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NoStackTrace

class PoolMetricsSpec extends CatsEffectSuite {
  import PoolMetricsSpec._

  test("Metrics should be empty for unused pool") {
    val sdk = createSdk
    val expectedSnapshot =
      MetricsSnapshot(Nil, Nil, Nil, Nil, Nil)

    for {
      snapshot <- mkPool(sdk.otel.meterProvider).use(_ => sdk.snapshot)
    } yield assertEquals(snapshot, expectedSnapshot)
  }

  test("In use: increment on acquire and decrement on release") {
    poolTest() { (sdk, pool) =>
      for {
        inUse <- pool.take.use(_ => sdk.snapshot)
        afterUse <- sdk.snapshot
      } yield {
        assertEquals(inUse.inUse, List(1L))
        assertEquals(afterUse.inUse, List(0L))
      }
    }
  }

  test("In use: increment on acquire and decrement on release (failure)") {
    val exception = new RuntimeException("Something went wrong") with NoStackTrace

    poolTest() { (sdk, pool) =>
      for {
        deferred <- IO.deferred[MetricsSnapshot]
        _ <- pool.take
          .use(_ => sdk.snapshot.flatMap(deferred.complete) >> IO.raiseError(exception))
          .attempt
        inUse <- deferred.get
        afterUse <- sdk.snapshot
      } yield {
        assertEquals(inUse.inUse, List(1L))
        assertEquals(afterUse.inUse, List(0L))
      }
    }
  }

  test("Idle: keep 0 when `maxIdle` is 0") {
    poolTest(_.withMaxIdle(0)) { (sdk, pool) =>
      for {
        inUse <- pool.take.use(_ => sdk.snapshot)
        afterUse <- sdk.snapshot
      } yield {
        assertEquals(inUse.idle, Nil)
        assertEquals(afterUse.idle, Nil)
      }
    }
  }

  test("Idle: keep 1 when `maxIdle` is 1") {
    poolTest(_.withMaxIdle(1)) { (sdk, pool) =>
      for {
        inUse <- pool.take.use(_ => sdk.snapshot)
        afterUse <- sdk.snapshot
      } yield {
        assertEquals(inUse.idle, Nil)
        assertEquals(afterUse.idle, List(1L))
      }
    }
  }

  test("Idle: decrement on reaper cleanup") {
    TestControl.executeEmbed {
      poolTest(_.withMaxIdle(1).withIdleTimeAllowedInPool(1.second)) { (sdk, pool) =>
        for {
          inUse <- pool.take.use(_ => sdk.snapshot)
          afterUse <- sdk.snapshot
          afterSleep <- sdk.snapshot.delayBy(6.seconds)
        } yield {
          assertEquals(inUse.idle, Nil)
          assertEquals(afterUse.idle, List(1L))
          assertEquals(afterSleep.idle, List(0L))
        }
      }
    }
  }

  test("Generate valid metric snapshots") {
    val sdk = createSdk

    TestControl.executeEmbed {
      mkPool(sdk.otel.meterProvider)
        .use(pool => pool.take.use(_ => sdk.snapshot.delayBy(500.millis)).product(sdk.snapshot))
        .map { case (inUse, afterUse) =>
          val acquireDuration =
            List(
              new HistogramPointData(0, 1, HistogramBuckets, List(1, 0, 0, 0, 0))
            )

          val expectedInUse = MetricsSnapshot(
            idle = Nil,
            inUse = List(1),
            inUseDuration = Nil,
            acquiredTotal = List(1),
            acquireDuration = acquireDuration
          )

          val expectedAfterUser = MetricsSnapshot(
            idle = List(1),
            inUse = List(0),
            inUseDuration = List(
              new HistogramPointData(500.0, 1, HistogramBuckets, List(0, 0, 0, 1, 0))
            ),
            acquiredTotal = List(1),
            acquireDuration = acquireDuration
          )

          assertEquals(inUse, expectedInUse)
          assertEquals(afterUse, expectedAfterUser)
        }
    }
  }

  private def poolTest(
      customize: Pool.Builder[IO, Ref[IO, Int]] => Pool.Builder[IO, Ref[IO, Int]] = identity
  )(scenario: (OtelSdk[IO], Pool[IO, Ref[IO, Int]]) => IO[Unit]): IO[Unit] = {
    val sdk = createSdk

    val builder =
      Pool.Builder(Ref.of[IO, Int](1), nothing).withMeterProvider(sdk.otel.meterProvider)

    customize(builder).build.use(pool => scenario(sdk, pool))
  }

  private def mkPool(meterProvider: MeterProvider[IO]) =
    Pool
      .Builder(
        Ref.of[IO, Int](1),
        nothing
      )
      .withMeterProvider(meterProvider)
      .withMaxTotal(10)
      .build

  private def createSdk: OtelSdk[IO] = {
    def customize(builder: SdkMeterProviderBuilder) =
      builder
        .registerView(
          InstrumentSelector.builder().setType(InstrumentType.HISTOGRAM).build(),
          View
            .builder()
            .setAggregation(
              Aggregation.explicitBucketHistogram(HistogramBuckets.map(Double.box).asJava)
            )
            .build()
        )

    val sdk = Sdk.create[IO](customize)

    new OtelSdk[IO] {
      val otel: Otel4s[IO] = OtelJava.forSync[IO](sdk.sdk)

      def snapshot: IO[MetricsSnapshot] =
        for {
          metrics <- sdk.metrics
        } yield {
          def counterValue(name: String): List[Long] =
            metrics
              .find(_.name == name)
              .map(_.data)
              .collectFirst { case MetricData.LongSum(points) =>
                points.map(_.value)
              }
              .getOrElse(Nil)

          def histogramSnapshot(name: String): List[HistogramPointData] =
            metrics
              .find(_.name == name)
              .map(_.data)
              .collectFirst { case MetricData.Histogram(points) =>
                points.map(_.value)
              }
              .getOrElse(Nil)

          MetricsSnapshot(
            counterValue("idle"),
            counterValue("in_use"),
            histogramSnapshot("in_use_duration"),
            counterValue("acquired_total"),
            histogramSnapshot("acquire_duration")
          )
        }
    }
  }

  private val HistogramBuckets: List[Double] =
    List(0.01, 1, 100, 1000)

  private def nothing(ref: Ref[IO, Int]): IO[Unit] =
    ref.get.void

}

object PoolMetricsSpec {

  trait OtelSdk[F[_]] {
    def otel: Otel4s[F]
    def snapshot: F[MetricsSnapshot]
  }

  final case class MetricsSnapshot(
      idle: List[Long],
      inUse: List[Long],
      inUseDuration: List[HistogramPointData],
      acquiredTotal: List[Long],
      acquireDuration: List[HistogramPointData]
  )

}
