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

package org.typelevel.keypool.otel4s

import java.util.concurrent.TimeUnit

import cats.effect.kernel.{Clock, Ref, Resource, Temporal}
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.traverse.*
import org.typelevel.keypool.Metrics
import org.typelevel.otel4s.{Attribute, Attributes}
import org.typelevel.otel4s.metrics.{BucketBoundaries, Histogram, MeterProvider}

import scala.concurrent.duration.FiniteDuration

object Otel4sMetrics {

  /** Configuration for an emitted instrument. */
  sealed trait InstrumentConfig {

    /** Instrument name. */
    def name: String

    /** Instrument unit. */
    def unit: String

    /** Instrument description. */
    def description: String

  }

  object InstrumentConfig {

    /** Resource-destruction counter configuration. */
    sealed trait Counter extends InstrumentConfig {

      /** Attributes added to each measurement, based on why the resource was destroyed. */
      def attributes: Metrics.DestructionReason => Attributes
    }

    /** Up-down counter configuration. */
    sealed trait UpDownCounter extends InstrumentConfig {

      /** Attributes added to each measurement. */
      def attributes: Attributes
    }

    /** Duration histogram configuration. */
    sealed trait Histogram extends InstrumentConfig {

      /** Attributes added to each measurement, based on how the measured resource use completed. */
      def attributes: Resource.ExitCase => Attributes

      /** Unit used to record durations. */
      def timeUnit: TimeUnit

      /** Histogram boundaries in [[timeUnit]]. */
      def explicitBucketBoundaries: BucketBoundaries

      final def unit: String =
        timeUnit match {
          case TimeUnit.NANOSECONDS => "ns"
          case TimeUnit.MICROSECONDS => "us"
          case TimeUnit.MILLISECONDS => "ms"
          case TimeUnit.SECONDS => "s"
          case TimeUnit.MINUTES => "min"
          case TimeUnit.HOURS => "h"
          case TimeUnit.DAYS => "d"
        }
    }

    /** Creates an up-down counter configuration. */
    def upDownCounter(
        name: String,
        unit: String,
        description: String,
        attributes: Attributes
    ): UpDownCounter =
      UpDownCounterImpl(name, unit, description, attributes)

    /** Creates a resource-destruction counter configuration. */
    def counter(
        name: String,
        unit: String,
        description: String,
        attributes: Attributes
    ): Counter =
      counter(name, unit, description, _ => attributes)

    /** Creates a resource-destruction counter with reason-dependent attributes. */
    def counter(
        name: String,
        unit: String,
        description: String,
        attributes: Metrics.DestructionReason => Attributes
    ): Counter =
      CounterImpl(name, unit, description, attributes)

    /** Creates a duration histogram configuration. */
    def histogram(
        name: String,
        timeUnit: TimeUnit,
        description: String,
        attributes: Attributes,
        explicitBucketBoundaries: BucketBoundaries
    ): Histogram =
      histogram(name, timeUnit, description, _ => attributes, explicitBucketBoundaries)

    /** Creates a duration histogram configuration with exit-case-dependent attributes. */
    def histogram(
        name: String,
        timeUnit: TimeUnit,
        description: String,
        attributes: Resource.ExitCase => Attributes,
        explicitBucketBoundaries: BucketBoundaries
    ): Histogram =
      HistogramImpl(name, timeUnit, description, attributes, explicitBucketBoundaries)

    private final case class UpDownCounterImpl(
        name: String,
        unit: String,
        description: String,
        attributes: Attributes
    ) extends UpDownCounter

    private final case class CounterImpl(
        name: String,
        unit: String,
        description: String,
        attributes: Metrics.DestructionReason => Attributes
    ) extends Counter

    private final case class HistogramImpl(
        name: String,
        timeUnit: TimeUnit,
        description: String,
        attributes: Resource.ExitCase => Attributes,
        explicitBucketBoundaries: BucketBoundaries
    ) extends Histogram

  }

  /** Configuration for [[Otel4sMetrics]]. */
  sealed trait Config {
    private[otel4s] def meterName: String
    private[otel4s] def constAttributes: Attributes
    private[otel4s] def idleInstrument: Option[InstrumentConfig.UpDownCounter]
    private[otel4s] def inUseInstrument: Option[InstrumentConfig.UpDownCounter]
    private[otel4s] def useDurationInstrument: Option[InstrumentConfig.Histogram]
    private[otel4s] def pendingAcquireInstrument: Option[InstrumentConfig.UpDownCounter]
    private[otel4s] def acquireDurationInstrument: Option[InstrumentConfig.Histogram]
    private[otel4s] def createDurationInstrument: Option[InstrumentConfig.Histogram]
    private[otel4s] def destroyedInstrument: Option[InstrumentConfig.Counter]

    /** Replaces the constant attributes attached to every measurement. */
    def withConstAttributes(attributes: Attributes): Config

    /** Appends constant attributes to every measurement. */
    def addConstAttributes(head: Attribute[?], tail: Attribute[?]*): Config

    /** Replaces the idle-resource instrument. */
    def withIdleInstrument(instrument: InstrumentConfig.UpDownCounter): Config

    /** Disables the idle-resource instrument. */
    def withoutIdle: Config

    /** Replaces the in-use-resource instrument. */
    def withInUseInstrument(instrument: InstrumentConfig.UpDownCounter): Config

    /** Disables the in-use-resource instrument. */
    def withoutInUse: Config

    /** Replaces the in-use-duration instrument. */
    def withUseDurationInstrument(instrument: InstrumentConfig.Histogram): Config

    /** Disables the in-use-duration instrument. */
    def withoutUseDuration: Config

    /** Replaces the pending-acquisition instrument. */
    def withPendingAcquireInstrument(instrument: InstrumentConfig.UpDownCounter): Config

    /** Disables the pending-acquisition instrument. */
    def withoutPendingAcquire: Config

    /** Replaces the acquire-duration instrument. */
    def withAcquireDurationInstrument(instrument: InstrumentConfig.Histogram): Config

    /** Disables the acquire-duration instrument. */
    def withoutAcquireDuration: Config

    /** Replaces the resource-creation-duration instrument. */
    def withCreateDurationInstrument(instrument: InstrumentConfig.Histogram): Config

    /** Disables the resource-creation-duration instrument. */
    def withoutCreateDuration: Config

    /** Replaces the resource-destruction counter. */
    def withDestroyedInstrument(instrument: InstrumentConfig.Counter): Config

    /** Disables the resource-destruction counter. */
    def withoutDestroyed: Config
  }

  object Config {

    /** Default instrument configuration. */
    object Defaults {
      val meterName: String = "org.typelevel.keypool"

      val histogramBucketBoundaries: BucketBoundaries =
        BucketBoundaries(.005, .01, .025, .05, .075, .1, .25, .5, .75, 1, 2.5, 5, 7.5, 10)

      val idleInstrument: InstrumentConfig.UpDownCounter =
        InstrumentConfig.upDownCounter(
          name = "keypool.resource.count",
          unit = "{resource}",
          description = "The number of resources currently in the pool, by state.",
          attributes = Attributes(Attribute("keypool.resource.state", "idle"))
        )

      val inUseInstrument: InstrumentConfig.UpDownCounter =
        InstrumentConfig.upDownCounter(
          name = "keypool.resource.count",
          unit = "{resource}",
          description = "The number of resources currently in the pool, by state.",
          attributes = Attributes(Attribute("keypool.resource.state", "used"))
        )

      val useDurationInstrument: InstrumentConfig.Histogram =
        InstrumentConfig.histogram(
          name = "keypool.resource.use.duration",
          timeUnit = TimeUnit.SECONDS,
          description = "The duration between borrowing a resource and returning it to the pool.",
          attributes = Attributes.empty,
          explicitBucketBoundaries = histogramBucketBoundaries
        )

      val pendingAcquireInstrument: InstrumentConfig.UpDownCounter =
        InstrumentConfig.upDownCounter(
          name = "keypool.acquire.pending",
          unit = "{request}",
          description = "The number of requests currently waiting to acquire a resource.",
          attributes = Attributes.empty
        )

      val acquireDurationInstrument: InstrumentConfig.Histogram =
        InstrumentConfig.histogram(
          name = "keypool.acquire.duration",
          timeUnit = TimeUnit.SECONDS,
          description = "The time it took to obtain a resource from the pool.",
          attributes = Attributes.empty,
          explicitBucketBoundaries = histogramBucketBoundaries
        )

      val createDurationInstrument: InstrumentConfig.Histogram =
        InstrumentConfig.histogram(
          name = "keypool.resource.create.duration",
          timeUnit = TimeUnit.SECONDS,
          description = "The time it took to create a new resource.",
          attributes = Attributes.empty,
          explicitBucketBoundaries = histogramBucketBoundaries
        )

      val destroyedInstrument: InstrumentConfig.Counter =
        InstrumentConfig.counter(
          name = "keypool.resource.destroyed",
          unit = "{resource}",
          description = "The number of resources removed permanently from the pool.",
          attributes = reason => Attributes(Attribute("keypool.destroy.reason", reasonName(reason)))
        )

      private def reasonName(reason: Metrics.DestructionReason): String =
        reason match {
          case Metrics.DestructionReason.IdleTimeout => "idle_timeout"
          case Metrics.DestructionReason.MaxIdle => "max_idle"
          case Metrics.DestructionReason.MaxPerKey => "max_per_key"
          case Metrics.DestructionReason.NotReusable => "not_reusable"
          case Metrics.DestructionReason.PoolClosed => "pool_closed"
        }
    }

    /** Default metrics configuration. */
    val default: Config =
      ConfigImpl(
        meterName = Defaults.meterName,
        constAttributes = Attributes.empty,
        idleInstrument = Some(Defaults.idleInstrument),
        inUseInstrument = Some(Defaults.inUseInstrument),
        useDurationInstrument = Some(Defaults.useDurationInstrument),
        pendingAcquireInstrument = Some(Defaults.pendingAcquireInstrument),
        acquireDurationInstrument = Some(Defaults.acquireDurationInstrument),
        createDurationInstrument = Some(Defaults.createDurationInstrument),
        destroyedInstrument = Some(Defaults.destroyedInstrument)
      )

    private final case class ConfigImpl(
        meterName: String,
        constAttributes: Attributes,
        idleInstrument: Option[InstrumentConfig.UpDownCounter],
        inUseInstrument: Option[InstrumentConfig.UpDownCounter],
        useDurationInstrument: Option[InstrumentConfig.Histogram],
        pendingAcquireInstrument: Option[InstrumentConfig.UpDownCounter],
        acquireDurationInstrument: Option[InstrumentConfig.Histogram],
        createDurationInstrument: Option[InstrumentConfig.Histogram],
        destroyedInstrument: Option[InstrumentConfig.Counter]
    ) extends Config {

      def withConstAttributes(attributes: Attributes): Config =
        copy(constAttributes = attributes)

      def addConstAttributes(head: Attribute[?], tail: Attribute[?]*): Config =
        copy(constAttributes = constAttributes + head ++ tail)

      def withIdleInstrument(instrument: InstrumentConfig.UpDownCounter): Config =
        copy(idleInstrument = Some(instrument))

      def withoutIdle: Config =
        copy(idleInstrument = None)

      def withInUseInstrument(instrument: InstrumentConfig.UpDownCounter): Config =
        copy(inUseInstrument = Some(instrument))

      def withoutInUse: Config =
        copy(inUseInstrument = None)

      def withUseDurationInstrument(instrument: InstrumentConfig.Histogram): Config =
        copy(useDurationInstrument = Some(instrument))

      def withoutUseDuration: Config =
        copy(useDurationInstrument = None)

      def withPendingAcquireInstrument(instrument: InstrumentConfig.UpDownCounter): Config =
        copy(pendingAcquireInstrument = Some(instrument))

      def withoutPendingAcquire: Config =
        copy(pendingAcquireInstrument = None)

      def withAcquireDurationInstrument(instrument: InstrumentConfig.Histogram): Config =
        copy(acquireDurationInstrument = Some(instrument))

      def withoutAcquireDuration: Config =
        copy(acquireDurationInstrument = None)

      def withCreateDurationInstrument(instrument: InstrumentConfig.Histogram): Config =
        copy(createDurationInstrument = Some(instrument))

      def withoutCreateDuration: Config =
        copy(createDurationInstrument = None)

      def withDestroyedInstrument(instrument: InstrumentConfig.Counter): Config =
        copy(destroyedInstrument = Some(instrument))

      def withoutDestroyed: Config =
        copy(destroyedInstrument = None)
    }

  }

  /**
   * Creates metrics provider using otel4s `MeterProvider`.
   *
   * @example
   *   {{{
   * val config = Otel4sMetrics.Config.default
   *   .withConstAttributes(Attributes(Attribute("pool.name", "db-pool")))
   *
   * Otel4sMetrics.provider[IO](config)
   *   }}}
   */
  def provider[F[_]: Temporal: MeterProvider](
      config: Config
  ): Metrics.Provider[F] =
    new Metrics.Provider[F] {
      def get: F[Metrics[F]] =
        for {
          meter <- MeterProvider[F].meter(config.meterName).withVersion(BuildInfo.version).get

          idle <- config.idleInstrument.traverse { instrument =>
            meter
              .upDownCounter[Long](instrument.name)
              .withUnit(instrument.unit)
              .withDescription(instrument.description)
              .create
              .tupleLeft(instrument)
          }

          inUse <- config.inUseInstrument.traverse { instrument =>
            meter
              .upDownCounter[Long](instrument.name)
              .withUnit(instrument.unit)
              .withDescription(instrument.description)
              .create
              .tupleLeft(instrument)
          }

          useDurationHistogram <- config.useDurationInstrument.traverse { instrument =>
            meter
              .histogram[Double](instrument.name)
              .withUnit(instrument.unit)
              .withDescription(instrument.description)
              .withExplicitBucketBoundaries(instrument.explicitBucketBoundaries)
              .create
              .tupleLeft(instrument)
          }

          pendingAcquire <- config.pendingAcquireInstrument.traverse { instrument =>
            meter
              .upDownCounter[Long](instrument.name)
              .withUnit(instrument.unit)
              .withDescription(instrument.description)
              .create
              .tupleLeft(instrument)
          }

          acquireDuration <- config.acquireDurationInstrument.traverse { instrument =>
            meter
              .histogram[Double](instrument.name)
              .withUnit(instrument.unit)
              .withDescription(instrument.description)
              .withExplicitBucketBoundaries(instrument.explicitBucketBoundaries)
              .create
              .tupleLeft(instrument)
          }

          createDurationHistogram <- config.createDurationInstrument.traverse { instrument =>
            meter
              .histogram[Double](instrument.name)
              .withUnit(instrument.unit)
              .withDescription(instrument.description)
              .withExplicitBucketBoundaries(instrument.explicitBucketBoundaries)
              .create
              .tupleLeft(instrument)
          }

          destroyed <- config.destroyedInstrument.traverse { instrument =>
            meter
              .counter[Long](instrument.name)
              .withUnit(instrument.unit)
              .withDescription(instrument.description)
              .create
              .tupleLeft(instrument)
          }
        } yield new Metrics.Unsealed[F] {

          private def attributes(instrument: InstrumentConfig.UpDownCounter): Attributes =
            config.constAttributes ++ instrument.attributes

          private def attributes(
              instrument: InstrumentConfig.Counter,
              reason: Metrics.DestructionReason
          ): Attributes =
            config.constAttributes ++ instrument.attributes(reason)

          private def attributes(
              instrument: InstrumentConfig.Histogram
          ): Resource.ExitCase => Attributes =
            exitCase => config.constAttributes ++ instrument.attributes(exitCase)

          private def recordElapsed(
              histogram: Option[(InstrumentConfig.Histogram, Histogram[F, Double])],
              startedAt: FiniteDuration,
              exitCase: Resource.ExitCase
          ): F[Unit] =
            histogram.fold(Temporal[F].unit) { case (instrument, histogram) =>
              Clock[F].monotonic.flatMap { finishedAt =>
                val duration =
                  (finishedAt - startedAt).toNanos.toDouble / instrument.timeUnit
                    .toNanos(1L)
                    .toDouble
                histogram.record(duration, attributes(instrument)(exitCase))
              }
            }

          /**
           * Completes when the resource is ready, while the finalizer covers failed or canceled
           * acquisitions. We cannot rely on the finalizer alone because it runs when the borrowed
           * resource is returned, which would include use time and keep the pending count elevated.
           * The guard ensures the metrics are recorded only once.
           */
          val acquire: Resource[F, Metrics.Acquisition[F]] =
            for {
              startedAt <- Resource.eval(Clock[F].monotonic)
              completed <- Resource.eval(Ref.of[F, Boolean](false))
              _ <- Resource.eval(
                pendingAcquire.fold(Temporal[F].unit) { case (instrument, counter) =>
                  counter.inc(attributes(instrument))
                }
              )
              acquisition = new Metrics.Acquisition[F] {
                def complete: F[Unit] =
                  finish(Resource.ExitCase.Succeeded)

                private[keypool] def finish(exitCase: Resource.ExitCase): F[Unit] =
                  Temporal[F].uncancelable { _ =>
                    completed.flatModify {
                      case true => (true, Temporal[F].unit)
                      case false =>
                        val decrementPending =
                          pendingAcquire.fold(Temporal[F].unit) { case (instrument, counter) =>
                            counter.dec(attributes(instrument))
                          }
                        (
                          true,
                          decrementPending >> recordElapsed(acquireDuration, startedAt, exitCase)
                        )
                    }
                  }
              }
              _ <- Resource.onFinalizeCase(acquisition.finish)
            } yield acquisition

          val idleInc: F[Unit] =
            idle.fold(Temporal[F].unit) { case (instrument, counter) =>
              counter.inc(attributes(instrument))
            }

          val idleDec: F[Unit] =
            idle.fold(Temporal[F].unit) { case (instrument, counter) =>
              counter.dec(attributes(instrument))
            }

          val inUseCount: Resource[F, Unit] =
            inUse.fold(Resource.unit[F]) { case (instrument, counter) =>
              Resource.make(counter.inc(attributes(instrument)))(_ =>
                counter.dec(attributes(instrument))
              )
            }

          val useDuration: Resource[F, Unit] =
            useDurationHistogram.fold(Resource.unit[F]) { case (instrument, histogram) =>
              histogram.recordDuration(instrument.timeUnit, attributes(instrument))
            }

          val createDuration: Resource[F, Unit] =
            createDurationHistogram.fold(Resource.unit[F]) { case (instrument, histogram) =>
              histogram.recordDuration(instrument.timeUnit, attributes(instrument))
            }

          def resourceDestroyed(reason: Metrics.DestructionReason): F[Unit] =
            destroyed.fold(Temporal[F].unit) { case (instrument, counter) =>
              counter.inc(attributes(instrument, reason))
            }
        }
    }
}
