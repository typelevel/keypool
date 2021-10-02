package org.typelevel.keypool

import cats._
import cats.effect.kernel._
import cats.syntax.all._
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
      val kpDefaultReuseState: Reusable,
      val idleTimeAllowedInPool: Duration,
      val kpMaxTotal: Int,
      val onReaperException: Throwable => F[Unit]
  ) {
    private def copy(
        kpRes: Resource[F, B] = this.kpRes,
        kpDefaultReuseState: Reusable = this.kpDefaultReuseState,
        idleTimeAllowedInPool: Duration = this.idleTimeAllowedInPool,
        kpMaxTotal: Int = this.kpMaxTotal,
        onReaperException: Throwable => F[Unit] = this.onReaperException
    ): Builder[F, B] = new Builder[F, B](
      kpRes,
      kpDefaultReuseState,
      idleTimeAllowedInPool,
      kpMaxTotal,
      onReaperException
    )

    def doOnCreate(f: B => F[Unit]): Builder[F, B] =
      copy(kpRes = this.kpRes.flatMap(v => Resource.eval(f(v).attempt.void.as(v))))

    def doOnDestroy(f: B => F[Unit]): Builder[F, B] =
      copy(kpRes =
        this.kpRes.flatMap(v => Resource.make(Applicative[F].unit)(_ => f(v).attempt.void).as(v))
      )

    def withDefaultReuseState(defaultReuseState: Reusable) =
      copy(kpDefaultReuseState = defaultReuseState)

    def withIdleTimeAllowedInPool(duration: Duration) =
      copy(idleTimeAllowedInPool = duration)

    def withMaxTotal(total: Int): Builder[F, B] =
      copy(kpMaxTotal = total)

    def withOnReaperException(f: Throwable => F[Unit]) =
      copy(onReaperException = f)

    private def toKeyPoolBuilder: KeyPool.Builder[F, Unit, B] =
      new KeyPool.Builder(
        kpRes = _ => kpRes,
        kpDefaultReuseState = kpDefaultReuseState,
        idleTimeAllowedInPool = idleTimeAllowedInPool,
        kpMaxPerKey = _ => kpMaxTotal,
        kpMaxTotal = kpMaxTotal,
        onReaperException = onReaperException
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
      Defaults.defaultReuseState,
      Defaults.idleTimeAllowedInPool,
      Defaults.maxTotal,
      Defaults.onReaperException[F]
    )

    def apply[F[_]: Temporal, B](
        create: F[B],
        destroy: B => F[Unit]
    ): Builder[F, B] =
      apply(Resource.make(create)(destroy))

    private object Defaults {
      val defaultReuseState = Reusable.Reuse
      val idleTimeAllowedInPool = 30.seconds
      val maxTotal = 100
      def onReaperException[F[_]: Applicative] = { (t: Throwable) =>
        Function.const(Applicative[F].unit)(t)
      }
    }
  }
}
