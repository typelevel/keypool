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

import cats._
import cats.effect.kernel._
import cats.syntax.all._
import org.typelevel.otel4s.metrics.MeterProvider
import scala.concurrent.duration._

/**
 * This pools internal guarantees are that the max number of values are in the pool at any time, not
 * maximum number of operations. To do the latter application level bounds should be used.
 *
 * A background reaper thread is kept alive for the length of the pools life.
 *
 * When resources are taken from the pool they are received as a [[Managed]]. This [[Managed]] has a
 * Ref to a [[Reusable]] which indicates whether or not the pool can reuse the resource.
 */

trait Pool[F[_], B] {

  /**
   * Take a [[Managed]] from the Pool.
   *
   * At the end of the resource lifetime the state of the resource controls whether it is submitted
   * back to the pool or removed.
   */
  def take: Resource[F, Managed[F, B]]

  /**
   * The current state of the pool.
   */
  def state: F[PoolStats]
}

object Pool {

  //
  // Instances
  //
  implicit def poolFunctor[F[_]]: Functor[Pool[F, *]] =
    new PFunctor[F]

  private class PFunctor[F[_]] extends Functor[Pool[F, *]] {
    override def map[A, B](fa: Pool[F, A])(f: A => B): Pool[F, B] =
      new Pool[F, B] {
        def take: Resource[F, Managed[F, B]] =
          fa.take.map(_.map(f))
        def state: F[PoolStats] =
          fa.state
      }
  }

  final class Builder[F[_]: Temporal, B] private (
      val kpRes: Resource[F, B],
      val name: String,
      val kpDefaultReuseState: Reusable,
      val idleTimeAllowedInPool: Duration,
      val kpMaxIdle: Int,
      val kpMaxTotal: Int,
      val onReaperException: Throwable => F[Unit],
      val meterProvider: MeterProvider[F]
  ) {
    private def copy(
        kpRes: Resource[F, B] = this.kpRes,
        name: String = this.name,
        kpDefaultReuseState: Reusable = this.kpDefaultReuseState,
        idleTimeAllowedInPool: Duration = this.idleTimeAllowedInPool,
        kpMaxIdle: Int = this.kpMaxIdle,
        kpMaxTotal: Int = this.kpMaxTotal,
        onReaperException: Throwable => F[Unit] = this.onReaperException,
        meterProvider: MeterProvider[F] = this.meterProvider
    ): Builder[F, B] = new Builder[F, B](
      kpRes,
      name = name,
      kpDefaultReuseState,
      idleTimeAllowedInPool,
      kpMaxIdle,
      kpMaxTotal,
      onReaperException,
      meterProvider
    )

    def doOnCreate(f: B => F[Unit]): Builder[F, B] =
      copy(kpRes = this.kpRes.flatMap(v => Resource.eval(f(v).attempt.void.as(v))))

    def doOnDestroy(f: B => F[Unit]): Builder[F, B] =
      copy(kpRes =
        this.kpRes.flatMap(v => Resource.make(Applicative[F].unit)(_ => f(v).attempt.void).as(v))
      )

    def withDefaultReuseState(defaultReuseState: Reusable): Builder[F, B] =
      copy(kpDefaultReuseState = defaultReuseState)

    def withIdleTimeAllowedInPool(duration: Duration): Builder[F, B] =
      copy(idleTimeAllowedInPool = duration)

    def withMaxIdle(maxIdle: Int): Builder[F, B] =
      copy(kpMaxIdle = maxIdle)

    def withMaxTotal(total: Int): Builder[F, B] =
      copy(kpMaxTotal = total)

    def withOnReaperException(f: Throwable => F[Unit]): Builder[F, B] =
      copy(onReaperException = f)

    def withMeterProvider(meterProvider: MeterProvider[F]): Builder[F, B] =
      copy(meterProvider = meterProvider)

    def withName(name: String): Builder[F, B] =
      copy(name = name)

    private def toKeyPoolBuilder: KeyPool.Builder[F, Unit, B] =
      new KeyPool.Builder(
        kpRes = _ => kpRes,
        name = name,
        kpDefaultReuseState = kpDefaultReuseState,
        idleTimeAllowedInPool = idleTimeAllowedInPool,
        kpMaxPerKey = _ => kpMaxTotal,
        kpMaxIdle = kpMaxIdle,
        kpMaxTotal = kpMaxTotal,
        onReaperException = onReaperException,
        meterProvider = meterProvider
      )

    def build: Resource[F, Pool[F, B]] = {
      toKeyPoolBuilder.build.map { inner =>
        new Pool[F, B] {
          def take: Resource[F, Managed[F, B]] = inner.take(())
          def state: F[PoolStats] = inner.state.map(s => new PoolStats(s._1))
        }
      }
    }
  }

  object Builder {
    def apply[F[_]: Temporal, B](
        res: Resource[F, B]
    ): Builder[F, B] = new Builder[F, B](
      res,
      Defaults.name,
      Defaults.defaultReuseState,
      Defaults.idleTimeAllowedInPool,
      Defaults.maxIdle,
      Defaults.maxTotal,
      Defaults.onReaperException[F],
      Defaults.meterProvider
    )

    def apply[F[_]: Temporal, B](
        create: F[B],
        destroy: B => F[Unit]
    ): Builder[F, B] =
      apply(Resource.make(create)(destroy))

    private object Defaults {
      val defaultReuseState = Reusable.Reuse
      val idleTimeAllowedInPool = 30.seconds
      val maxIdle = 100
      val maxTotal = 100
      val name = "unknown"
      def onReaperException[F[_]: Applicative] = { (t: Throwable) =>
        Function.const(Applicative[F].unit)(t)
      }
      def meterProvider[F[_]: Applicative]: MeterProvider[F] = MeterProvider.noop
    }
  }
}
