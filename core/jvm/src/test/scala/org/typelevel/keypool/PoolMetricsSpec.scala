package org.typelevel.keypool

import java.util.concurrent.TimeUnit

import cats.syntax.all._
import cats.effect._
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.{
  Aggregation,
  InstrumentSelector,
  InstrumentType,
  SdkMeterProvider,
  View
}
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import munit.CatsEffectSuite
import org.typelevel.otel4s.{MeterProvider, Otel4s}
import org.typelevel.otel4s.java.OtelJava
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NoStackTrace

class PoolMetricsSpec extends CatsEffectSuite {
  import PoolMetricsSpec._

  test("Metrics should be empty for unused pool") {
    val expectedSnapshot = MetricsSnapshot(0, 0, Nil, 0, Nil)

    for {
      snapshot <- setupSdk.use(sdk => mkPool(sdk.otel.meterProvider).use(_ => sdk.snapshot))
    } yield assertEquals(snapshot, expectedSnapshot)
  }

  test("In use: increment on acquire and decrement on release") {
    poolTest() { (sdk, pool) =>
      for {
        inUse <- pool.take.use(_ => sdk.snapshot)
        afterUse <- sdk.snapshot
      } yield {
        assertEquals(inUse.inUse, 1L)
        assertEquals(afterUse.inUse, 0L)
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
        assertEquals(inUse.inUse, 1L)
        assertEquals(afterUse.inUse, 0L)
      }
    }
  }

  test("Idle: keep 0 when `maxIdle` is 0") {
    poolTest(_.withMaxIdle(0)) { (sdk, pool) =>
      for {
        inUse <- pool.take.use(_ => sdk.snapshot)
        afterUse <- sdk.snapshot
      } yield {
        assertEquals(inUse.idle, 0L)
        assertEquals(afterUse.idle, 0L)
      }
    }
  }

  test("Idle: keep 1 when `maxIdle` is 1") {
    poolTest(_.withMaxIdle(1)) { (sdk, pool) =>
      for {
        inUse <- pool.take.use(_ => sdk.snapshot)
        afterUse <- sdk.snapshot
      } yield {
        assertEquals(inUse.idle, 0L)
        assertEquals(afterUse.idle, 1L)
      }
    }
  }

  test("Idle: decrement on reaper cleanup") {
    poolTest(_.withMaxIdle(1).withIdleTimeAllowedInPool(1.second)) { (sdk, pool) =>
      for {
        inUse <- pool.take.use(_ => sdk.snapshot)
        afterUse <- sdk.snapshot
        afterSleep <- sdk.snapshot.delayBy(6.seconds)
      } yield {
        assertEquals(inUse.idle, 0L)
        assertEquals(afterUse.idle, 1L)
        assertEquals(afterSleep.idle, 0L)
      }
    }
  }

  test("Generate valid metric snapshots") {
    setupSdk.use { sdk =>
      mkPool(sdk.otel.meterProvider)
        .use(pool => pool.take.use(_ => sdk.snapshot.delayBy(500.millis)).product(sdk.snapshot))
        .map { case (inUse, afterUse) =>
          val acquireDuration =
            List(HistogramSnapshot(0, 1, HistogramBuckets, List(0, 1, 0, 0, 0)))

          val expectedInUse = MetricsSnapshot(
            idle = 0,
            inUse = 1,
            inUseDuration = Nil,
            acquiredTotal = 1,
            acquireDuration = acquireDuration
          )

          val expectedAfterUser = MetricsSnapshot(
            idle = 1,
            inUse = 0,
            inUseDuration = List(HistogramSnapshot(0, 1, HistogramBuckets, List(0, 0, 0, 1, 0))),
            acquiredTotal = 1,
            acquireDuration = acquireDuration
          )

          assertEquals(inUse.zeroSumHistogram, expectedInUse)
          assertEquals(afterUse.zeroSumHistogram, expectedAfterUser)
          assert(afterUse.inUseDuration.forall(r => r.sum >= 500 && r.sum <= 700))
        }
    }
  }

  private def poolTest(
      customize: Pool.Builder[IO, Ref[IO, Int]] => Pool.Builder[IO, Ref[IO, Int]] = identity
  )(scenario: (OtelSdk[IO], Pool[IO, Ref[IO, Int]]) => IO[Unit]): IO[Unit] =
    setupSdk.use { sdk =>
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

  private def setupSdk: Resource[IO, OtelSdk[IO]] = {
    val acquire = IO.delay {
      val mr = InMemoryMetricReader.create()

      val mp = SdkMeterProvider
        .builder()
        .registerMetricReader(mr)
        .registerView(
          InstrumentSelector.builder().setType(InstrumentType.HISTOGRAM).build(),
          View
            .builder()
            .setAggregation(
              Aggregation.explicitBucketHistogram(HistogramBuckets.map(Double.box).asJava)
            )
            .build()
        )
        .build()

      val sdk = OpenTelemetrySdk
        .builder()
        .setMeterProvider(mp)
        .build()

      new OtelSdk[IO] {
        def metricReader: InMemoryMetricReader = mr
        def otel: Otel4s[IO] = OtelJava.forSync[IO](sdk)
        def flush: IO[Unit] = IO.blocking {
          val _ = mp.forceFlush().join(5, TimeUnit.SECONDS)
          ()
        }

        def snapshot: IO[MetricsSnapshot] = {
          IO.delay {
            val items = metricReader.collectAllMetrics().asScala.toList

            def counterValue(name: String): Long =
              items
                .find(_.getName === name)
                .flatMap(_.getLongSumData.getPoints.asScala.headOption.map(_.getValue))
                .getOrElse(0L)

            def histogramSnapshot(name: String): List[HistogramSnapshot] =
              items
                .find(_.getName === name)
                .map { metric =>
                  val points = metric.getHistogramData.getPoints.asScala.toList

                  points.map { hdp =>
                    HistogramSnapshot(
                      hdp.getSum,
                      hdp.getCount,
                      hdp.getBoundaries.asScala.toList.map(Double.unbox),
                      hdp.getCounts.asScala.toList.map(Long.unbox)
                    )
                  }
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
    }

    def release(sdk: OtelSdk[IO]) = sdk.flush

    Resource.make(acquire)(release)
  }

  private val HistogramBuckets: List[Double] =
    List(0.01, 1, 100, 1000)

  private def nothing(ref: Ref[IO, Int]): IO[Unit] =
    ref.get.void

}

object PoolMetricsSpec {

  trait OtelSdk[F[_]] {
    def metricReader: InMemoryMetricReader
    def otel: Otel4s[F]
    def flush: F[Unit]
    def snapshot: F[MetricsSnapshot]
  }

  final case class MetricsSnapshot(
      idle: Long,
      inUse: Long,
      inUseDuration: List[HistogramSnapshot],
      acquiredTotal: Long,
      acquireDuration: List[HistogramSnapshot]
  ) {
    // use 0 for `histogram#sum` to simplify the comparison
    def zeroSumHistogram: MetricsSnapshot =
      copy(
        inUseDuration = inUseDuration.map(_.zeroSum),
        acquireDuration = acquireDuration.map(_.zeroSum)
      )
  }

  final case class HistogramSnapshot(
      sum: Double,
      count: Long,
      boundaries: List[Double],
      counts: List[Long]
  ) {
    def zeroSum: HistogramSnapshot = copy(sum = 0)
  }

}
