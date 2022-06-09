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

import internal.{PoolList, PoolMap}
import cats._
import cats.syntax.all._
import cats.effect.kernel._
import cats.effect.kernel.syntax.spawn._
import scala.concurrent.duration._

@deprecated("use KeyPool.Builder", "0.4.7")
final class KeyPoolBuilder[F[_]: Temporal, A, B] private (
    val kpCreate: A => F[B],
    val kpDestroy: B => F[Unit],
    val kpDefaultReuseState: Reusable,
    val idleTimeAllowedInPool: Duration,
    val kpMaxPerKey: A => Int,
    val kpMaxTotal: Int,
    val onReaperException: Throwable => F[Unit]
) {
  private def copy(
      kpCreate: A => F[B] = this.kpCreate,
      kpDestroy: B => F[Unit] = this.kpDestroy,
      kpDefaultReuseState: Reusable = this.kpDefaultReuseState,
      idleTimeAllowedInPool: Duration = this.idleTimeAllowedInPool,
      kpMaxPerKey: A => Int = this.kpMaxPerKey,
      kpMaxTotal: Int = this.kpMaxTotal,
      onReaperException: Throwable => F[Unit] = this.onReaperException
  ): KeyPoolBuilder[F, A, B] = new KeyPoolBuilder[F, A, B](
    kpCreate,
    kpDestroy,
    kpDefaultReuseState,
    idleTimeAllowedInPool,
    kpMaxPerKey,
    kpMaxTotal,
    onReaperException
  )

  def doOnCreate(f: B => F[Unit]): KeyPoolBuilder[F, A, B] =
    copy(kpCreate = { (k: A) => this.kpCreate(k).flatMap(v => f(v).attempt.void.as(v)) })

  def doOnDestroy(f: B => F[Unit]): KeyPoolBuilder[F, A, B] =
    copy(kpDestroy = { (r: B) => f(r).attempt.void >> this.kpDestroy(r) })

  def withDefaultReuseState(defaultReuseState: Reusable) =
    copy(kpDefaultReuseState = defaultReuseState)

  def withIdleTimeAllowedInPool(duration: Duration) =
    copy(idleTimeAllowedInPool = duration)

  def withMaxPerKey(f: A => Int): KeyPoolBuilder[F, A, B] =
    copy(kpMaxPerKey = f)

  def withMaxTotal(total: Int): KeyPoolBuilder[F, A, B] =
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
          keepRunning(KeyPool.reap(nanos, kpVar, onReaperException)).background.void
        case _ =>
          Applicative[Resource[F, *]].unit
      }
    } yield new KeyPool.KeyPoolConcrete(
      (a: A) => Resource.make[F, B](kpCreate(a))(kpDestroy),
      kpDefaultReuseState,
      kpMaxPerKey,
      kpMaxTotal,
      kpVar
    )
  }

}

object KeyPoolBuilder {
  @deprecated("use KeyPool.Builder.apply", "0.4.7")
  def apply[F[_]: Temporal, A, B](
      create: A => F[B],
      destroy: B => F[Unit]
  ): KeyPoolBuilder[F, A, B] = new KeyPoolBuilder[F, A, B](
    create,
    destroy,
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
