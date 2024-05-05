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

import cats.effect._
import cats.effect.testkit._
import munit.CatsEffectSuite
import org.typelevel.keypool.internal.Metrics
import org.typelevel.otel4s.{Attribute, Attributes}
import org.typelevel.otel4s.metrics.{BucketBoundaries, Meter, MeterProvider}
import org.typelevel.otel4s.sdk.metrics.data.{MetricPoints, PointData, TimeWindow}
import org.typelevel.otel4s.sdk.testkit.metrics.MetricsTestkit

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class PoolMetricsSpec extends CatsEffectSuite {
  import PoolMetricsSpec._

  test("Metrics should be empty for unused pool") {
    val expectedSnapshot =
      MetricsSnapshot(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty)

    createTestkit.use { testkit =>
      for {
        snapshot <- mkPool(testkit.metrics.meterProvider).surround(testkit.snapshot)
      } yield assertEquals(snapshot, expectedSnapshot)
    }
  }

  test("In use: increment on acquire and decrement on release") {
    poolTest() { (sdk, pool) =>
      for {
        inUse <- pool.take.surround(sdk.snapshot)
        afterUse <- sdk.snapshot
      } yield {
        assertEquals(inUse.inUse, Vector(1L))
        assertEquals(afterUse.inUse, Vector(0L))
      }
    }
  }

  test("In use: increment on acquire and decrement on release (failure)") {
    val exception = new RuntimeException("Something went wrong") with NoStackTrace

    poolTest() { (sdk, pool) =>
      for {
        deferred <- IO.deferred[MetricsSnapshot]
        _ <- pool.take
          .surround(sdk.snapshot.flatMap(deferred.complete) >> IO.raiseError(exception))
          .attempt
        inUse <- deferred.get
        afterUse <- sdk.snapshot
      } yield {
        assertEquals(inUse.inUse, Vector(1L))
        assertEquals(afterUse.inUse, Vector(0L))
      }
    }
  }

  test("Idle: keep 0 when `maxIdle` is 0") {
    poolTest(_.withMaxIdle(0)) { (sdk, pool) =>
      for {
        inUse <- pool.take.surround(sdk.snapshot)
        afterUse <- sdk.snapshot
      } yield {
        assertEquals(inUse.idle, Vector.empty)
        assertEquals(afterUse.idle, Vector.empty)
      }
    }
  }

  test("Idle: keep 1 when `maxIdle` is 1") {
    poolTest(_.withMaxIdle(1)) { (sdk, pool) =>
      for {
        inUse <- pool.take.surround(sdk.snapshot)
        afterUse <- sdk.snapshot
      } yield {
        assertEquals(inUse.idle, Vector.empty)
        assertEquals(afterUse.idle, Vector(1L))
      }
    }
  }

  test("Idle: decrement on reaper cleanup") {
    poolTest(_.withMaxIdle(1).withIdleTimeAllowedInPool(1.second)) { (sdk, pool) =>
      for {
        inUse <- pool.take.surround(sdk.snapshot)
        afterUse <- sdk.snapshot
        afterSleep <- sdk.snapshot.delayBy(6.seconds)
      } yield {
        assertEquals(inUse.idle, Vector.empty)
        assertEquals(afterUse.idle, Vector(1L))
        assertEquals(afterSleep.idle, Vector(0L))
      }
    }

  }

  test("Generate valid metric snapshots") {
    poolTest() { (sdk, pool) =>
      pool.take
        .surround(sdk.snapshot.delayBy(1.second))
        .product(sdk.snapshot)
        .map { case (inUse, afterUse) =>
          val acquireDuration = Vector(
            PointData.histogram(
              TimeWindow(Duration.Zero, 1.second),
              Attributes(Attribute("pool.name", "test")),
              Vector.empty,
              Some(PointData.Histogram.Stats(0.0, 0.0, 0.0, 1)),
              HistogramBuckets,
              Vector(1, 0, 0, 0, 0)
            )
          )

          val expectedInUse = MetricsSnapshot(
            idle = Vector.empty,
            inUse = Vector(1L),
            inUseDuration = Vector.empty,
            acquiredTotal = Vector(1L),
            acquireDuration = acquireDuration
          )

          val expectedAfterUser = MetricsSnapshot(
            idle = Vector(1L),
            inUse = Vector(0L),
            inUseDuration = Vector(
              PointData.histogram(
                TimeWindow(Duration.Zero, 1.second),
                Attributes(Attribute("pool.name", "test")),
                Vector.empty,
                Some(PointData.Histogram.Stats(1.0, 1.0, 1.0, 1)),
                HistogramBuckets,
                Vector(0, 1, 0, 0, 0)
              )
            ),
            acquiredTotal = Vector(1L),
            acquireDuration = acquireDuration
          )

          assertEquals(inUse, expectedInUse)
          assertEquals(afterUse, expectedAfterUser)
        }
    }
  }

  private def poolTest(
      customize: Pool.Builder[IO, Ref[IO, Int]] => Pool.Builder[IO, Ref[IO, Int]] = identity
  )(scenario: (OtelTestkit[IO], Pool[IO, Ref[IO, Int]]) => IO[Unit]): IO[Unit] =
    TestControl.executeEmbed {
      createTestkit.use { sdk =>
        sdk.metrics.meterProvider.get("org.typelevel.keypool").flatMap { implicit M: Meter[IO] =>
          val builder = Pool
            .Builder(Ref.of[IO, Int](1), nothing)
            .withMetricsProvider(metricsProvider)

          customize(builder).build.use(pool => scenario(sdk, pool))
        }
      }
    }

  private def mkPool(meterProvider: MeterProvider[IO]) =
    Resource.eval(meterProvider.get("org.typelevel.keypool")).flatMap { implicit M: Meter[IO] =>
      Pool
        .Builder(
          Ref.of[IO, Int](1),
          nothing
        )
        .withMetricsProvider(metricsProvider)
        .withMaxTotal(10)
        .build
    }

  private def metricsProvider(implicit M: Meter[IO]): Metrics.Provider[IO] =
    Otel4sMetrics.provider[IO](
      "keypool",
      Attributes(Attribute("pool.name", "test")),
      HistogramBuckets,
      HistogramBuckets
    )

  private def createTestkit: Resource[IO, OtelTestkit[IO]] =
    MetricsTestkit.inMemory[IO]().map { testkit =>
      new OtelTestkit[IO] {
        val metrics: MetricsTestkit[IO] = testkit

        def snapshot: IO[MetricsSnapshot] =
          for {
            metrics <- testkit.collectMetrics
          } yield {
            def counterValue(name: String): Vector[Long] =
              metrics
                .find(_.name == name)
                .map(_.data)
                .collectFirst { case sum: MetricPoints.Sum =>
                  sum.points.toVector.collect { case long: PointData.LongNumber =>
                    long.value
                  }
                }
                .getOrElse(Vector.empty)

            def histogramSnapshot(name: String): Vector[PointData.Histogram] =
              metrics
                .find(_.name == name)
                .map(_.data)
                .collectFirst { case histogram: MetricPoints.Histogram =>
                  histogram.points.toVector
                }
                .getOrElse(Vector.empty)

            MetricsSnapshot(
              counterValue("keypool.idle.current"),
              counterValue("keypool.in_use.current"),
              histogramSnapshot("keypool.in_use.duration"),
              counterValue("keypool.acquired.total"),
              histogramSnapshot("keypool.acquire.duration")
            )
          }
      }
    }

  private val HistogramBuckets: BucketBoundaries =
    BucketBoundaries(Vector(0.01, 1.0, 100.0, 1000.0))

  private def nothing(ref: Ref[IO, Int]): IO[Unit] =
    ref.get.void

}

object PoolMetricsSpec {

  trait OtelTestkit[F[_]] {
    def metrics: MetricsTestkit[F]
    def snapshot: F[MetricsSnapshot]
  }

  final case class MetricsSnapshot(
      idle: Vector[Long],
      inUse: Vector[Long],
      inUseDuration: Vector[PointData.Histogram],
      acquiredTotal: Vector[Long],
      acquireDuration: Vector[PointData.Histogram]
  )

}
