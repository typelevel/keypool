package org.typelevel.keypool.internal

import cats.Monad
import cats.syntax.functor._
import cats.syntax.flatMap._
import org.typelevel.otel4s._

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
