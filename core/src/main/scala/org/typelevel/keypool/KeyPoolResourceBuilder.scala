package org.typelevel.keypool

import internal.{PoolList, PoolMap}
import cats._
import cats.syntax.all._
import cats.effect.kernel._
import scala.concurrent.duration._

final class KeyPoolResourceBuilder[F[_]: Temporal, A, B] private (
    val kpRes: A => Resource[F, B],
    val kpDefaultReuseState: Reusable,
    val idleTimeAllowedInPool: Duration,
    val kpMaxPerKey: A => Int,
    val kpMaxTotal: Int,
    val onReaperException: Throwable => F[Unit]
) {
  private def copy(
      kpRes: A => Resource[F, B] = this.kpRes,
      kpDefaultReuseState: Reusable = this.kpDefaultReuseState,
      idleTimeAllowedInPool: Duration = this.idleTimeAllowedInPool,
      kpMaxPerKey: A => Int = this.kpMaxPerKey,
      kpMaxTotal: Int = this.kpMaxTotal,
      onReaperException: Throwable => F[Unit] = this.onReaperException
  ): KeyPoolResourceBuilder[F, A, B] = new KeyPoolResourceBuilder[F, A, B](
    kpRes,
    kpDefaultReuseState,
    idleTimeAllowedInPool,
    kpMaxPerKey,
    kpMaxTotal,
    onReaperException
  )

  def doOnCreate(f: B => F[Unit]): KeyPoolResourceBuilder[F, A, B] =
    copy(kpRes = { (k: A) => this.kpRes(k).flatMap(v => Resource.eval(f(v).attempt.void.as(v))) })

  def doOnDestroy(f: B => F[Unit]): KeyPoolResourceBuilder[F, A, B] =
    copy(kpRes = { (k: A) =>
      this.kpRes(k).flatMap(v => Resource.make(Applicative[F].unit)(_ => f(v).attempt.void).as(v))
    })

  def withDefaultReuseState(defaultReuseState: Reusable) =
    copy(kpDefaultReuseState = defaultReuseState)

  def withIdleTimeAllowedInPool(duration: Duration) =
    copy(idleTimeAllowedInPool = duration)

  def withMaxPerKey(f: A => Int): KeyPoolResourceBuilder[F, A, B] =
    copy(kpMaxPerKey = f)

  def withMaxTotal(total: Int): KeyPoolResourceBuilder[F, A, B] =
    copy(kpMaxTotal = total)

  def withOnReaperException(f: Throwable => F[Unit]) =
    copy(onReaperException = f)

  def build: Resource[F, KeyPool[F, A, B]] = {
    def keepRunning[Z](fa: F[Z]): F[Z] =
      fa.onError { case e => onReaperException(e) }.attempt >> keepRunning(fa)
    for {
      kpVar <- Resource.make(
        Ref[F].of[PoolMap[A, (B, F[Unit])]](PoolMap.open(0, Map.empty[A, PoolList[(B, F[Unit])]]))
      )(kpVar => KeyPool.destroy(kpVar))
      _ <- idleTimeAllowedInPool match {
        case fd: FiniteDuration =>
          val nanos = 0.seconds.max(fd)
          Resource.make(
            Concurrent[F].start(keepRunning(KeyPool.reap(nanos, kpVar, onReaperException)))
          )(_.cancel)
        case _ =>
          Applicative[Resource[F, *]].unit
      }
    } yield new KeyPool.KeyPoolConcrete(
      kpRes,
      kpDefaultReuseState,
      kpMaxPerKey,
      kpMaxTotal,
      kpVar
    )
  }

}

object KeyPoolResourceBuilder {
  def apply[F[_]: Temporal, A, B](
      res: A => Resource[F, B]
  ): KeyPoolResourceBuilder[F, A, B] = new KeyPoolResourceBuilder[F, A, B](
    res,
    Defaults.defaultReuseState,
    Defaults.idleTimeAllowedInPool,
    Defaults.maxPerKey,
    Defaults.maxTotal,
    Defaults.onReaperException[F]
  )

  private object Defaults {
    val defaultReuseState = Reusable.Reuse
    val idleTimeAllowedInPool = 30.seconds
    def maxPerKey[K](k: K): Int = Function.const(100)(k)
    val maxTotal = 100
    def onReaperException[F[_]: Applicative] = { (t: Throwable) =>
      Function.const(Applicative[F].unit)(t)
    }
  }
}
