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
import scala.concurrent.duration._

/**
 * This pools internal guarantees are that the max number of values are in the pool at any time, not
 * maximum number of operations. To do the latter application level bounds should be used.
 *
 * A background reaper thread is kept alive for the length of the pool's life.
 *
 * When resources are taken from the pool they are received as a [[Managed]]. This [[Managed]] has a
 * [[Ref]] to a [[Reusable]] which indicates whether the pool can reuse the resource.
 *
 * `Pool` is a convenience, single-key specialization of [[KeyPool]] that does not partition
 * resources by key and exposes simpler `take`/`state` APIs.
 *
 * @see
 *   [[KeyPool]]
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
      val kpDefaultReuseState: Reusable,
      val idleTimeAllowedInPool: Duration,
      val durationBetweenEvictionRuns: Duration,
      val kpMaxIdle: Int,
      val kpMaxTotal: Int,
      val fairness: Fairness,
      val onReaperException: Throwable => F[Unit]
  ) {
    private def copy(
        kpRes: Resource[F, B] = this.kpRes,
        kpDefaultReuseState: Reusable = this.kpDefaultReuseState,
        idleTimeAllowedInPool: Duration = this.idleTimeAllowedInPool,
        durationBetweenEvictionRuns: Duration = this.durationBetweenEvictionRuns,
        kpMaxIdle: Int = this.kpMaxIdle,
        kpMaxTotal: Int = this.kpMaxTotal,
        fairness: Fairness = this.fairness,
        onReaperException: Throwable => F[Unit] = this.onReaperException
    ): Builder[F, B] = new Builder[F, B](
      kpRes,
      kpDefaultReuseState,
      idleTimeAllowedInPool,
      durationBetweenEvictionRuns,
      kpMaxIdle,
      kpMaxTotal,
      fairness,
      onReaperException
    )

    /**
     * Register a callback `f` to run after a new [[Managed]] item is created. Any error raised by
     * the callback is ignored, and the created item is returned unchanged.
     *
     * @note
     *   If multiple callbacks are registered, their execution order is not guaranteed and callers
     *   should not rely on any particular ordering.
     */
    def doOnCreate(f: B => F[Unit]): Builder[F, B] =
      copy(kpRes = this.kpRes.flatMap(v => Resource.eval(f(v).attempt.void.as(v))))

    /**
     * Register a callback `f` to run when a [[Managed]] item is about to be destroyed. Any error
     * raised by the callback is ignored, and the item will be destroyed regardless.
     *
     * @note
     *   If multiple callbacks are registered, their execution order is not guaranteed and callers
     *   should not rely on any particular ordering.
     */
    def doOnDestroy(f: B => F[Unit]): Builder[F, B] =
      copy(kpRes =
        this.kpRes.flatMap(v => Resource.make(Applicative[F].unit)(_ => f(v).attempt.void).as(v))
      )

    /**
     * Set the default [[Reusable]] state applied when resources are returned to the pool. This
     * default can be overridden per-resource via [[Managed.canBeReused]] from [[Pool.take]]. If not
     * configured, the default is [[Reusable.Reuse]].
     *
     * @param defaultReuseState
     *   whether resources returned to the pool should be reused ([[Reusable.Reuse]]) or destroyed
     *   ([[Reusable.DontReuse]]) by default.
     */
    def withDefaultReuseState(defaultReuseState: Reusable): Builder[F, B] =
      copy(kpDefaultReuseState = defaultReuseState)

    /**
     * Set how long an idle resource is allowed to remain in the pool before it becomes eligible for
     * eviction. If not configured, the builder defaults to 30 seconds.
     *
     * @param duration
     *   maximum idle time allowed in the pool.
     */
    def withIdleTimeAllowedInPool(duration: Duration): Builder[F, B] =
      copy(idleTimeAllowedInPool = duration)

    /**
     * Set the interval between eviction runs of the pool reaper. If not configured, the builder
     * defaults to 5 seconds.
     *
     * @param duration
     *   time between successive eviction runs.
     */
    def withDurationBetweenEvictionRuns(duration: Duration): Builder[F, B] =
      copy(durationBetweenEvictionRuns = duration)

    /**
     * Set the maximum number of idle resources allowed across the pool. If not configured, the
     * builder defaults to 100.
     *
     * @param maxIdle
     *   maximum idle resources in the pool.
     */
    def withMaxIdle(maxIdle: Int): Builder[F, B] =
      copy(kpMaxIdle = maxIdle)

    /**
     * Set the maximum total number of concurrent resources permitted by the pool. If not
     * configured, the builder defaults to 100.
     *
     * @param total
     *   maximum total resources.
     */
    def withMaxTotal(total: Int): Builder[F, B] =
      copy(kpMaxTotal = total)

    /**
     * Set the [[Fairness]] policy for acquiring permits from the global semaphore controlling total
     * resources. If not configured, the builder defaults to [[Fairness.Fifo]].
     *
     * @param fairness
     *   fairness policy - [[Fairness.Fifo]] or [[Fairness.Lifo]].
     */
    def withFairness(fairness: Fairness): Builder[F, B] =
      copy(fairness = fairness)

    /**
     * Register a handler `f` invoked for any `Throwable` observed by the background reaper; it runs
     * when the reaper loop reports an error.
     *
     * The provided function is invoked with any `Throwable` observed in the reaper loop. If the
     * handler itself fails, that failure is swallowed and thereby ignored - the reaper will
     * continue running and may invoke the handler again for subsequent errors.
     */
    def withOnReaperException(f: Throwable => F[Unit]): Builder[F, B] =
      copy(onReaperException = f)

    private def toKeyPoolBuilder: KeyPool.Builder[F, Unit, B] =
      new KeyPool.Builder(
        kpRes = _ => kpRes,
        kpDefaultReuseState = kpDefaultReuseState,
        idleTimeAllowedInPool = idleTimeAllowedInPool,
        durationBetweenEvictionRuns = durationBetweenEvictionRuns,
        kpMaxPerKey = _ => kpMaxTotal,
        kpMaxIdle = kpMaxIdle,
        kpMaxTotal = kpMaxTotal,
        fairness = fairness,
        onReaperException = onReaperException
      )

    /**
     * Create a `Pool` wrapped in a `Resource`, initializing pool internals from the configured
     * builder parameters and defaults.
     *
     * @note
     *   The implementation is a thin single-key specialization that delegates to
     *   [[KeyPool.Builder]] under the hood.
     */
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

    /**
     * Create a new `Builder` for a `Pool` from a `Resource` that produces values stored in the
     * pool.
     */
    def apply[F[_]: Temporal, B](
        res: Resource[F, B]
    ): Builder[F, B] = new Builder[F, B](
      res,
      Defaults.defaultReuseState,
      Defaults.idleTimeAllowedInPool,
      Defaults.durationBetweenEvictionRuns,
      Defaults.maxIdle,
      Defaults.maxTotal,
      Defaults.fairness,
      Defaults.onReaperException[F]
    )

    /**
     * Convenience constructor that accepts `create` and `destroy` functions and builds a `Resource`
     * internally.
     */
    def apply[F[_]: Temporal, B](
        create: F[B],
        destroy: B => F[Unit]
    ): Builder[F, B] =
      apply(Resource.make(create)(destroy))

    private object Defaults {
      val defaultReuseState = Reusable.Reuse
      val idleTimeAllowedInPool = 30.seconds
      val durationBetweenEvictionRuns = 5.seconds
      val maxIdle = 100
      val maxTotal = 100
      val fairness = Fairness.Fifo
      def onReaperException[F[_]: Applicative] = { (t: Throwable) =>
        Function.const(Applicative[F].unit)(t)
      }
    }
  }
}
