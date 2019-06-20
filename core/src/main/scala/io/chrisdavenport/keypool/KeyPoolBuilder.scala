package io.chrisdavenport.keypool

import internal.{PoolMap, PoolList}
import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import scala.concurrent.duration._

final class KeyPoolBuilder[F[_]: Concurrent: Timer, Key, Rezource] private (
  private val kpCreate: Key => F[Rezource],
  private val kpDestroy: (Key, Rezource) => F[Unit],
  private val kpDefaultReuseState: Reusable,
  private val idleTimeAllowedInPool: Duration,
  private val kpMaxPerKey: Key => Int,
  private val kpMaxTotal: Int,
  private val onReaperException: Throwable => F[Unit]
){
  private def copy(
    kpCreate: Key => F[Rezource] = this.kpCreate,
    kpDestroy: (Key, Rezource) => F[Unit] = this.kpDestroy,
    kpDefaultReuseState: Reusable = this.kpDefaultReuseState,
    idleTimeAllowedInPool: Duration = this.idleTimeAllowedInPool,
    kpMaxPerKey: Key => Int = this.kpMaxPerKey,
    kpMaxTotal: Int = this.kpMaxTotal,
    onReaperException: Throwable => F[Unit] = this.onReaperException
  ): KeyPoolBuilder[F, Key, Rezource] = new KeyPoolBuilder[F, Key, Rezource](
    kpCreate,
    kpDestroy,
    kpDefaultReuseState,
    idleTimeAllowedInPool,
    kpMaxPerKey,
    kpMaxTotal,
    onReaperException
  )

  def withDefaultReuseState(defaultReuseState: Reusable) =
    copy(kpDefaultReuseState = defaultReuseState)

  def withIdleTimeAllowedInPool(duration: Duration) =
    copy(idleTimeAllowedInPool = duration)

  def withMaxPerKey(f: Key => Int): KeyPoolBuilder[F, Key, Rezource] = 
    copy(kpMaxPerKey = f)

  def withMaxTotal(total: Int): KeyPoolBuilder[F, Key, Rezource] =
    copy(kpMaxTotal = total)
  
  def withOnReaperException(f: Throwable => F[Unit]) = 
    copy(onReaperException = f)

  def build: Resource[F, KeyPool[F, Key, Rezource]] = {
    def keepRunning[A](fa: F[A]): F[A] =
      fa.onError{ case e => onReaperException(e)}.attempt >> keepRunning(fa)
    for {
      kpVar <- Resource.make(
        Ref[F].of[PoolMap[Key, Rezource]](PoolMap.open(0, Map.empty[Key, PoolList[Rezource]]))
      )(kpVar => KeyPool.destroy(kpDestroy, kpVar))
      _ <- idleTimeAllowedInPool match {
        case fd: FiniteDuration =>
          val nanos = math.max(0L, fd.toNanos)
          Resource.make(Concurrent[F].start(keepRunning(KeyPool.reap(kpDestroy, nanos, kpVar, onReaperException))))(_.cancel)
        case _ =>
          Applicative[Resource[F, ?]].unit
      }
    } yield new KeyPool(
      kpCreate,
      kpDestroy,
      kpDefaultReuseState,
      kpMaxPerKey,
      kpMaxTotal,
      kpVar
    )
  }

  
}

object KeyPoolBuilder {
  def apply[F[_]: Concurrent: Timer, Key, Rezource](
    create: Key => F[Rezource],
    destroy: (Key, Rezource) => F[Unit],
  ): KeyPoolBuilder[F, Key, Rezource] = new KeyPoolBuilder[F, Key, Rezource](
    create,
    destroy, 
    Defaults.defaultReuseState,
    Defaults.idleTimeAllowedInPool,
    Defaults.maxPerKey,
    Defaults.maxTotal,
    Defaults.onReaperException[F]
  )

  private object  Defaults {
    val defaultReuseState = Reusable.Reuse
    val idleTimeAllowedInPool = 30.seconds
    def maxPerKey[K](k: K): Int = Function.const(100)(k)
    val maxTotal = 100
    def onReaperException[F[_]: Applicative] = {t: Throwable => Function.const(Applicative[F].unit)(t)}
  }
}