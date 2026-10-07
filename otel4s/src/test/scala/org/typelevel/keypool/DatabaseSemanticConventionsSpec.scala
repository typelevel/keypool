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

import cats.effect.*
import cats.effect.testkit.*
import munit.CatsEffectSuite
import org.typelevel.keypool.otel4s.Otel4sMetrics
import org.typelevel.otel4s.Attributes
import org.typelevel.otel4s.metrics.{BucketBoundaries, MeterProvider}
import org.typelevel.otel4s.sdk.metrics.data.{MetricData, PointData}
import org.typelevel.otel4s.sdk.testkit.AttributesExpectation
import org.typelevel.otel4s.sdk.testkit.metrics.{
  MetricExpectation,
  MetricExpectations,
  MetricsTestkit,
  PointExpectation,
  PointSetExpectation
}
import org.typelevel.otel4s.semconv.{MetricSpec, Requirement}
import org.typelevel.otel4s.semconv.experimental.attributes.DbExperimentalAttributes
import org.typelevel.otel4s.semconv.experimental.metrics.DbExperimentalMetrics

import scala.concurrent.duration.*

class DatabaseSemanticConventionsSpec extends CatsEffectSuite {

  test("Configure instruments to follow the database connection pool semantic conventions") {
    TestControl.executeEmbed {
      MetricsTestkit.inMemory[IO]().use { sdk =>
        implicit val meterProvider: MeterProvider[IO] = sdk.meterProvider

        val poolAttributes =
          Attributes(DbExperimentalAttributes.DbClientConnectionPoolName("test"))

        val connectionCountSpec = DbExperimentalMetrics.ClientConnectionCount
        val useTimeSpec = DbExperimentalMetrics.ClientConnectionUseTime
        val waitTimeSpec = DbExperimentalMetrics.ClientConnectionWaitTime
        val createTimeSpec = DbExperimentalMetrics.ClientConnectionCreateTime
        val pendingRequestsSpec = DbExperimentalMetrics.ClientConnectionPendingRequests

        def connectionCount(state: String): Otel4sMetrics.InstrumentConfig.UpDownCounter =
          Otel4sMetrics.InstrumentConfig.upDownCounter(
            name = connectionCountSpec.name,
            unit = connectionCountSpec.unit,
            description = connectionCountSpec.description,
            attributes = Attributes(DbExperimentalAttributes.DbClientConnectionState(state))
          )

        val config = Otel4sMetrics.Config.default
          .withConstAttributes(poolAttributes)
          .withIdleInstrument(connectionCount("idle"))
          .withInUseInstrument(connectionCount("used"))
          .withUseDurationInstrument(
            Otel4sMetrics.InstrumentConfig.histogram(
              name = useTimeSpec.name,
              timeUnit = TimeUnit.SECONDS,
              description = useTimeSpec.description,
              attributes = Attributes.empty,
              explicitBucketBoundaries = HistogramBuckets
            )
          )
          .withPendingAcquireInstrument(
            Otel4sMetrics.InstrumentConfig.upDownCounter(
              name = pendingRequestsSpec.name,
              unit = pendingRequestsSpec.unit,
              description = pendingRequestsSpec.description,
              attributes = Attributes.empty
            )
          )
          .withAcquireDurationInstrument(
            Otel4sMetrics.InstrumentConfig.histogram(
              name = waitTimeSpec.name,
              timeUnit = TimeUnit.SECONDS,
              description = waitTimeSpec.description,
              attributes = Attributes.empty,
              explicitBucketBoundaries = HistogramBuckets
            )
          )
          .withCreateDurationInstrument(
            Otel4sMetrics.InstrumentConfig.histogram(
              name = createTimeSpec.name,
              timeUnit = TimeUnit.SECONDS,
              description = createTimeSpec.description,
              attributes = Attributes.empty,
              explicitBucketBoundaries = HistogramBuckets
            )
          )
          .withoutDestroyed

        Pool
          .Builder(Ref.of[IO, Int](1), nothing)
          .withMetricsProvider(Otel4sMetrics.provider[IO](config))
          .withMaxIdle(1)
          .build
          .use { pool =>
            pool.take
              .surround(sdk.collectMetrics.delayBy(1.second))
              .product(sdk.collectMetrics)
              .map { case (inUse, afterUse) =>
                val usedAttributes =
                  poolAttributes + DbExperimentalAttributes.DbClientConnectionState("used")
                val idleAttributes =
                  poolAttributes + DbExperimentalAttributes.DbClientConnectionState("idle")

                val waitTime = semanticHistogram(waitTimeSpec)
                  .exactlyPoints(
                    PointExpectation.histogram
                      .stats(PointData.Histogram.Stats(0.0, 0.0, 0.0, 1))
                      .boundaries(HistogramBuckets)
                      .counts(1L, 0L, 0L, 0L, 0L)
                      .attributesExact(poolAttributes)
                  )
                val createTime = semanticHistogram(createTimeSpec)
                  .exactlyPoints(
                    PointExpectation.histogram
                      .stats(PointData.Histogram.Stats(0.0, 0.0, 0.0, 1))
                      .boundaries(HistogramBuckets)
                      .counts(1L, 0L, 0L, 0L, 0L)
                      .attributesExact(poolAttributes)
                  )
                val pendingRequests = semanticSum(pendingRequestsSpec)
                  .exactlyPoints(PointExpectation.numeric(0L).attributesExact(poolAttributes))

                assertNotEmitted(inUse, useTimeSpec.name)
                assertMetrics(
                  inUse,
                  semanticSum(connectionCountSpec)
                    .exactlyPoints(
                      PointExpectation.numeric(1L).attributesExact(usedAttributes)
                    ),
                  pendingRequests,
                  waitTime,
                  createTime
                )

                assertMetrics(
                  afterUse,
                  semanticSum(connectionCountSpec)
                    .exactlyPoints(
                      PointExpectation.numeric(1L).attributesExact(idleAttributes),
                      PointExpectation.numeric(0L).attributesExact(usedAttributes)
                    ),
                  pendingRequests,
                  semanticHistogram(useTimeSpec)
                    .exactlyPoints(
                      PointExpectation.histogram
                        .stats(PointData.Histogram.Stats(1.0, 1.0, 1.0, 1))
                        .boundaries(HistogramBuckets)
                        .counts(0L, 1L, 0L, 0L, 0L)
                        .attributesExact(poolAttributes)
                    ),
                  waitTime,
                  createTime
                )
              }
          }
      }
    }
  }

  private def semanticSum(spec: MetricSpec): MetricExpectation.Numeric[Long] = {
    val attributes = requiredAttributes(spec)

    MetricExpectation
      .sum[Long](spec.name)
      .description(spec.description)
      .unit(spec.unit)
      .pointsWhere("all points should have the attributes required by the semantic convention")(
        _.forall(point => attributes.matches(point.attributes))
      )
  }

  private def semanticHistogram(spec: MetricSpec): MetricExpectation.Histogram =
    MetricExpectation
      .histogram(spec.name)
      .description(spec.description)
      .unit(spec.unit)
      .points(
        PointSetExpectation.forall(
          PointExpectation.histogram.attributes(requiredAttributes(spec))
        )
      )

  private def requiredAttributes(spec: MetricSpec): AttributesExpectation = {
    val requiredKeys = spec.attributeSpecs.collect {
      case attribute if attribute.requirement.level == Requirement.Level.Required =>
        attribute.key
    }

    AttributesExpectation.where(
      s"required attributes: ${requiredKeys.map(_.name).sorted.mkString(", ")}"
    ) { attributes =>
      requiredKeys.forall(key => attributes.exists(_.key == key))
    }
  }

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

  private val HistogramBuckets: BucketBoundaries =
    BucketBoundaries(Vector(0.01, 1.0, 100.0, 1000.0))

  private def nothing(ref: Ref[IO, Int]): IO[Unit] =
    ref.get.void

}
