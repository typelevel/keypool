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

import cats.effect.*
import cats.effect.testkit.*
import munit.CatsEffectSuite
import org.typelevel.keypool.otel4s.Otel4sMetrics
import org.typelevel.otel4s.{Attribute, Attributes}
import org.typelevel.otel4s.metrics.{BucketBoundaries, MeterProvider}
import org.typelevel.otel4s.sdk.metrics.data.{MetricData, PointData, TimeWindow}
import org.typelevel.otel4s.sdk.testkit.metrics.{
  MetricExpectation,
  MetricExpectations,
  MetricsTestkit,
  PointExpectation
}

import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

class PoolMetricsSpec extends CatsEffectSuite {
  test("Metrics should be empty for unused pool") {
    createTestkit.use { testkit =>
      mkPool(testkit.meterProvider)
        .surround(testkit.collectMetrics)
        .map(metrics => assertEquals(metrics, Nil))
    }
  }

  test("In use: increment on acquire and decrement on release") {
    poolTest() { (sdk, pool) =>
      for {
        inUse <- pool.take.surround(sdk.collectMetrics)
        afterUse <- sdk.collectMetrics
      } yield {
        assertMetrics(inUse, resourceCount("used" -> 1L))
        assertMetrics(afterUse, resourceCount("idle" -> 1L, "used" -> 0L))
      }
    }
  }

  test("In use: increment on acquire and decrement on release (failure)") {
    val exception = new RuntimeException("Something went wrong") with NoStackTrace

    poolTest() { (sdk, pool) =>
      for {
        deferred <- IO.deferred[List[MetricData]]
        _ <- pool.take
          .surround(sdk.collectMetrics.flatMap(deferred.complete) >> IO.raiseError(exception))
          .attempt
        inUse <- deferred.get
        afterUse <- sdk.collectMetrics
      } yield {
        assertMetrics(inUse, resourceCount("used" -> 1L))
        assertMetrics(afterUse, resourceCount("idle" -> 1L, "used" -> 0L))
      }
    }
  }

  test("Idle: keep 0 when `maxIdle` is 0") {
    poolTest(_.withMaxIdle(0)) { (sdk, pool) =>
      for {
        inUse <- pool.take.surround(sdk.collectMetrics)
        afterUse <- sdk.collectMetrics
      } yield {
        assertMetrics(inUse, resourceCount("used" -> 1L))
        assertMetrics(
          afterUse,
          resourceCount("used" -> 0L),
          destroyedMetric("max_idle", 1L)
        )
      }
    }
  }

  test("Idle: keep 1 when `maxIdle` is 1") {
    poolTest(_.withMaxIdle(1)) { (sdk, pool) =>
      for {
        inUse <- pool.take.surround(sdk.collectMetrics)
        afterUse <- sdk.collectMetrics
      } yield {
        assertMetrics(inUse, resourceCount("used" -> 1L))
        assertMetrics(afterUse, resourceCount("idle" -> 1L, "used" -> 0L))
      }
    }
  }

  test("Idle: decrement on reaper cleanup") {
    poolTest(_.withMaxIdle(1).withIdleTimeAllowedInPool(1.second)) { (sdk, pool) =>
      for {
        inUse <- pool.take.surround(sdk.collectMetrics)
        afterUse <- sdk.collectMetrics
        afterSleep <- sdk.collectMetrics.delayBy(6.seconds)
      } yield {
        assertMetrics(inUse, resourceCount("used" -> 1L))
        assertMetrics(afterUse, resourceCount("idle" -> 1L, "used" -> 0L))
        assertMetrics(
          afterSleep,
          resourceCount("idle" -> 0L, "used" -> 0L),
          destroyedMetric("idle_timeout", 1L)
        )
      }
    }

  }

  test("Resource count remains accurate when an idle resource is reused") {
    poolTest(_.withMaxIdle(1)) { (sdk, pool) =>
      for {
        _ <- pool.take.use_ // create and return one idle resource
        duringReuse <- pool.take.surround(sdk.collectMetrics)
        afterReuse <- sdk.collectMetrics
      } yield {
        assertMetrics(duringReuse, resourceCount("idle" -> 0L, "used" -> 1L))
        assertMetrics(afterReuse, resourceCount("idle" -> 1L, "used" -> 0L))
      }
    }
  }

  test("Resource count returns to zero when the pool closes") {
    TestControl.executeEmbed {
      createTestkit.use { sdk =>
        implicit val meterProvider: MeterProvider[IO] = sdk.meterProvider
        for {
          _ <- Pool
            .Builder(Ref.of[IO, Int](1), nothing)
            .withMetricsProvider(metricsProvider)
            .withMaxIdle(1)
            .build
            .use(_.take.use_)
          afterClose <- sdk.collectMetrics
        } yield assertMetrics(
          afterClose,
          resourceCount("idle" -> 0L, "used" -> 0L),
          destroyedMetric("pool_closed", 1L)
        )
      }
    }
  }

  test("Pending count and acquire duration include time waiting for a permit") {
    poolTest(_.withMaxTotal(1).withMaxIdle(1)) { (sdk, pool) =>
      for {
        holderStarted <- IO.deferred[Unit]
        releaseHolder <- IO.deferred[Unit]
        holder <- pool.take.use(_ => holderStarted.complete(()) >> releaseHolder.get).start
        _ <- holderStarted.get
        waiter <- pool.take.use_.start
        pending <- sdk.collectMetrics.delayBy(1.second)
        _ <- releaseHolder.complete(())
        _ <- holder.joinWithNever
        _ <- waiter.joinWithNever
        completed <- sdk.collectMetrics
      } yield {
        assertMetrics(pending, currentMetric(PendingAcquire, 1L))
        assertMetrics(
          completed,
          currentMetric(PendingAcquire, 0L),
          histogramMetric(
            AcquireDuration,
            TimeWindow(Duration.Zero, 1.second),
            PointData.Histogram.Stats(1.0, 0.0, 1.0, 2),
            List(1, 1, 0, 0, 0)
          )
        )
      }
    }
  }

  test("Generate valid metrics") {
    poolTest() { (sdk, pool) =>
      pool.take
        .surround(sdk.collectMetrics.delayBy(1.second))
        .product(sdk.collectMetrics)
        .map { case (inUse, afterUse) =>
          val acquireDuration = histogramMetric(
            AcquireDuration,
            TimeWindow(Duration.Zero, 1.second),
            PointData.Histogram.Stats(0.0, 0.0, 0.0, 1),
            List(1, 0, 0, 0, 0)
          )
          val createDuration = histogramMetric(
            CreateDuration,
            TimeWindow(Duration.Zero, 1.second),
            PointData.Histogram.Stats(0.0, 0.0, 0.0, 1),
            List(1, 0, 0, 0, 0)
          )

          assertNotEmitted(inUse, UseDuration)
          assertMetrics(
            inUse,
            resourceCount("used" -> 1L),
            currentMetric(PendingAcquire, 0L),
            acquireDuration,
            createDuration
          )

          assertMetrics(
            afterUse,
            resourceCount("idle" -> 1L, "used" -> 0L),
            currentMetric(PendingAcquire, 0L),
            histogramMetric(
              UseDuration,
              TimeWindow(Duration.Zero, 1.second),
              PointData.Histogram.Stats(1.0, 1.0, 1.0, 1),
              List(0, 1, 0, 0, 0)
            ),
            acquireDuration,
            createDuration
          )
        }
    }
  }

  test("Histogram attributes can depend on the resource exit case") {
    val exception = new RuntimeException("Something went wrong") with NoStackTrace

    TestControl.executeEmbed {
      createTestkit.use { sdk =>
        implicit val meterProvider: MeterProvider[IO] = sdk.meterProvider

        val config = Otel4sMetrics.Config.default
          .withConstAttributes(PoolAttributes)
          .withoutIdle
          .withoutInUse
          .withUseDurationInstrument(
            Otel4sMetrics.InstrumentConfig.histogram(
              name = UseDuration,
              timeUnit = java.util.concurrent.TimeUnit.SECONDS,
              description = "For how long a resource is in use.",
              attributes =
                exitCase => Attributes(Attribute("pool.exit_case", exitCaseName(exitCase))),
              explicitBucketBoundaries = HistogramBuckets
            )
          )
          .withoutPendingAcquire
          .withoutAcquireDuration
          .withoutCreateDuration
          .withoutDestroyed

        Pool
          .Builder(Ref.of[IO, Int](1), nothing)
          .withMetricsProvider(Otel4sMetrics.provider[IO](config))
          .withMaxIdle(0)
          .build
          .use { pool =>
            for {
              _ <- pool.take.use(_ => IO.unit)
              _ <- pool.take.use(_ => IO.raiseError[Unit](exception)).attempt
              started <- IO.deferred[Unit]
              fiber <- pool.take.use(_ => started.complete(()) >> IO.never[Unit]).start
              _ <- started.get
              _ <- fiber.cancel
              metrics <- sdk.collectMetrics
            } yield assertMetrics(
              metrics,
              MetricExpectation
                .histogram(UseDuration)
                .exactlyPoints(
                  PointExpectation.histogram.attributesExact(exitCaseAttributes("succeeded")),
                  PointExpectation.histogram.attributesExact(exitCaseAttributes("errored")),
                  PointExpectation.histogram.attributesExact(exitCaseAttributes("canceled"))
                )
            )
          }
      }
    }
  }

  private def poolTest(
      customize: Pool.Builder[IO, Ref[IO, Int]] => Pool.Builder[IO, Ref[IO, Int]] = identity
  )(scenario: (MetricsTestkit[IO], Pool[IO, Ref[IO, Int]]) => IO[Unit]): IO[Unit] =
    TestControl.executeEmbed {
      createTestkit.use { sdk =>
        implicit val meterProvider: MeterProvider[IO] = sdk.meterProvider
        val builder = Pool
          .Builder(Ref.of[IO, Int](1), nothing)
          .withMetricsProvider(metricsProvider)

        customize(builder).build.use(pool => scenario(sdk, pool))
      }
    }

  private def mkPool(meterProvider: MeterProvider[IO]) = {
    implicit val implicitMeterProvider: MeterProvider[IO] = meterProvider
    Pool
      .Builder(
        Ref.of[IO, Int](1),
        nothing
      )
      .withMetricsProvider(metricsProvider)
      .withMaxTotal(10)
      .build
  }

  private def metricsProvider(implicit M: MeterProvider[IO]): Metrics.Provider[IO] =
    Otel4sMetrics.provider[IO](
      Otel4sMetrics.Config.default
        .withConstAttributes(PoolAttributes)
        .withUseDurationInstrument(
          Otel4sMetrics.InstrumentConfig.histogram(
            name = UseDuration,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS,
            description = "For how long a resource is in use.",
            attributes = Attributes.empty,
            explicitBucketBoundaries = HistogramBuckets
          )
        )
        .withAcquireDurationInstrument(
          Otel4sMetrics.InstrumentConfig.histogram(
            name = AcquireDuration,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS,
            description = "How long does it take to acquire a resource.",
            attributes = Attributes.empty,
            explicitBucketBoundaries = HistogramBuckets
          )
        )
        .withCreateDurationInstrument(
          Otel4sMetrics.InstrumentConfig.histogram(
            name = CreateDuration,
            timeUnit = java.util.concurrent.TimeUnit.SECONDS,
            description = "How long does it take to create a resource.",
            attributes = Attributes.empty,
            explicitBucketBoundaries = HistogramBuckets
          )
        )
    )

  private def createTestkit: Resource[IO, MetricsTestkit[IO]] =
    MetricsTestkit.inMemory[IO]()

  private def assertMetrics(metrics: List[MetricData], expected: MetricExpectation*): Unit =
    MetricExpectations.checkAll(metrics, expected.toList) match {
      case Right(_) => ()
      case Left(mismatches) => fail(MetricExpectations.format(mismatches))
    }

  private def assertNotEmitted(metrics: List[MetricData], names: String*): Unit =
    names.foreach { name =>
      assert(
        !MetricExpectations.exists(metrics, MetricExpectation.name(name)),
        clues(name, metrics.map(_.name))
      )
    }

  private def currentMetric(name: String, value: Long): MetricExpectation =
    MetricExpectation
      .sum[Long](name)
      .exactlyPoints(PointExpectation.numeric(value).attributesExact(PoolAttributes))

  private def resourceCount(points: (String, Long)*): MetricExpectation = {
    val expected = points.map { case (state, value) =>
      PointExpectation
        .numeric(value)
        .attributesExact(PoolAttributes + Attribute("keypool.resource.state", state))
    }
    MetricExpectation.sum[Long](ResourceCount).exactlyPoints(expected.head, expected.tail*)
  }

  private def destroyedMetric(reason: String, value: Long): MetricExpectation =
    MetricExpectation
      .sum[Long](Destroyed)
      .exactlyPoints(
        PointExpectation
          .numeric(value)
          .attributesExact(PoolAttributes + Attribute("keypool.destroy.reason", reason))
      )

  private def histogramMetric(
      name: String,
      timeWindow: TimeWindow,
      stats: PointData.Histogram.Stats,
      counts: List[Long]
  ): MetricExpectation =
    MetricExpectation
      .histogram(name)
      .exactlyPoints(
        PointExpectation.histogram
          .stats(stats)
          .boundaries(HistogramBuckets)
          .counts(counts)
          .attributesExact(PoolAttributes)
          .where(s"time window should be $timeWindow")(_.timeWindow == timeWindow)
      )

  private val HistogramBuckets: BucketBoundaries =
    BucketBoundaries(Vector(0.01, 1.0, 100.0, 1000.0))

  private val PoolAttributes: Attributes =
    Attributes(Attribute("pool.name", "test"))

  private def exitCaseAttributes(exitCase: String): Attributes =
    PoolAttributes + Attribute("pool.exit_case", exitCase)

  private def exitCaseName(exitCase: Resource.ExitCase): String =
    exitCase match {
      case Resource.ExitCase.Succeeded => "succeeded"
      case Resource.ExitCase.Errored(_) => "errored"
      case Resource.ExitCase.Canceled => "canceled"
    }

  private val ResourceCount = "keypool.resource.count"
  private val UseDuration = "keypool.resource.use.duration"
  private val PendingAcquire = "keypool.acquire.pending"
  private val AcquireDuration = "keypool.acquire.duration"
  private val CreateDuration = "keypool.resource.create.duration"
  private val Destroyed = "keypool.resource.destroyed"

  private def nothing(ref: Ref[IO, Int]): IO[Unit] =
    ref.get.void

}
