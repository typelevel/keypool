package org.typelevel.keypool.internal

import cats.Monad
import cats.effect.{Resource, Temporal}
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

  private[keypool] final def incIdle: F[Unit] = idle.add(1L)
  private[keypool] final def decIdle: F[Unit] = idle.add(-1L)
  private[keypool] final def incInUse: F[Unit] = inUse.add(1L)
  private[keypool] final def decInUse: F[Unit] = inUse.add(-1L)

  private[keypool] final def recordInUseDuration(implicit F: Temporal[F]): Resource[F, Unit] =
    Metrics.recordInUseDuration(this)

  private[keypool] final def recordAcquireDuration[A](
      resource: Resource[F, A]
  )(implicit F: Temporal[F]): F[(A, F[Unit])] =
    Metrics.recordAcquireDuration(this, resource)

}

private[keypool] object Metrics {

  private val CauseKey: AttributeKey[String] = AttributeKey.string("cause")

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

  private def recordInUseDuration[F[_]](
      metrics: Metrics[F]
  )(implicit F: Temporal[F]): Resource[F, Unit] =
    Resource
      .makeCase(Temporal[F].monotonic) { case (start, ec) =>
        for {
          end <- Temporal[F].monotonic
          _ <- metrics.inUseDuration.record((end - start).toMillis, causeAttributes(ec): _*)
        } yield ()
      }
      .void

  private def recordAcquireDuration[F[_], A](
      metrics: Metrics[F],
      resource: Resource[F, A]
  )(implicit F: Temporal[F]): F[(A, F[Unit])] =
    Temporal[F]
      .timed(resource.allocated)
      .flatMap { case (acquireTime, r) =>
        metrics.acquireDuration.record(acquireTime.toMillis).as(r)
      }

  private def causeAttributes(ec: Resource.ExitCase): List[Attribute[String]] =
    ec match {
      case Resource.ExitCase.Succeeded => Nil
      case Resource.ExitCase.Errored(e) => List(Attribute(CauseKey, e.getClass.getName))
      case Resource.ExitCase.Canceled => List(Attribute(CauseKey, "canceled"))
    }

}
