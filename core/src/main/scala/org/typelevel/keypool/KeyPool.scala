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
import cats.effect.kernel.syntax.spawn._
import cats.effect.std.Semaphore
import cats.syntax.all._
import scala.concurrent.duration._
import org.typelevel.keypool.internal._
import cats.effect.kernel.Resource.ExitCase

/**
 * This pools internal guarantees are that the max number of values are in the pool at any time, not
 * maximum number of operations. To do the latter application level bounds should be used.
 *
 * A background reaper thread is kept alive for the length of the key pools life.
 *
 * When resources are taken from the pool they are received as a [[Managed]]. This [[Managed]] has a
 * Ref to a [[Reusable]] which indicates whether or not the pool can reuse the resource.
 */

trait KeyPool[F[_], A, B] {

  /**
   * Take a [[Managed]] from the Pool. For the lifetime of this resource this is exclusively
   * available to this key.
   *
   * At the end of the resource lifetime the state of the resource controls whether it is submitted
   * back to the pool or removed.
   */
  def take(k: A): Resource[F, Managed[F, B]]

  /**
   * The current state of the pool.
   *
   * The left value is the total number of resources currently in the pool, and the right is a map
   * of how many resources exist for each key.
   */
  def state: F[(Int, Map[A, Int])]
}

object KeyPool {

  private[keypool] final class KeyPoolConcrete[F[_]: Temporal, A, B] private[keypool] (
      private[keypool] val kpRes: A => Resource[F, B],
      private[keypool] val kpDefaultReuseState: Reusable,
      private[keypool] val kpMaxPerKey: A => Int,
      private[keypool] val kpMaxIdle: Int,
      private[keypool] val kpMaxTotal: Int,
      private[keypool] val kpMaxTotalSem: Semaphore[F],
      private[keypool] val kpVar: Ref[F, PoolMap[A, (B, ExitCase => F[Unit])]]
  ) extends KeyPool[F, A, B] {

    def take(k: A): Resource[F, Managed[F, B]] =
      KeyPool.take(this, k)

    def state: F[(Int, Map[A, Int])] =
      KeyPool.state(kpVar)
  }

  //
  // Instances
  //
  implicit def keypoolFunctor[F[_], Z]: Functor[KeyPool[F, Z, *]] =
    new KPFunctor[F, Z]

  private class KPFunctor[F[_], Z] extends Functor[KeyPool[F, Z, *]] {
    override def map[A, B](fa: KeyPool[F, Z, A])(f: A => B): KeyPool[F, Z, B] =
      new KeyPool[F, Z, B] {
        def take(k: Z): Resource[F, Managed[F, B]] =
          fa.take(k).map(_.map(f))
        def state: F[(Int, Map[Z, Int])] = fa.state
      }
  }

  // Instance Is an AlleyCat Due to Map Functor instance
  // Must Explicitly Import
  def keypoolInvariant[F[_]: Functor, Z]: Invariant[KeyPool[F, *, Z]] =
    new KPInvariant[F, Z]

  private class KPInvariant[F[_]: Functor, Z] extends Invariant[KeyPool[F, *, Z]] {
    override def imap[A, B](fa: KeyPool[F, A, Z])(f: A => B)(g: B => A): KeyPool[F, B, Z] =
      new KeyPool[F, B, Z] {
        def take(k: B): Resource[F, Managed[F, Z]] =
          fa.take(g(k))
        def state: F[(Int, Map[B, Int])] = fa.state.map { case (total, m) =>
          (total, m.map { case (a, i) => (f(a), i) })
        }
      }
  }

  // Internal Helpers

  /**
   * Make a 'KeyPool' inactive and destroy all idle resources.
   */
  private[keypool] def destroy[F[_]: MonadThrow, A, B](
      kpVar: Ref[F, PoolMap[A, (B, ExitCase => F[Unit])]],
      exit: ExitCase
  ): F[Unit] =
    for {
      m <- kpVar.getAndSet(PoolMap.closed[A, (B, ExitCase => F[Unit])])
      _ <- m match {
        case PoolClosed() => Applicative[F].unit
        case PoolOpen(_, m2) =>
          m2.toList.traverse_ { case (_, pl) =>
            pl.toList
              .traverse_ { case (_, r) =>
                r._2(exit).attempt.void
              }
          }
      }
    } yield ()

  /**
   * Run a reaper thread, which will destroy old resources. It will stop running once our pool
   * switches to PoolClosed.
   */
  private[keypool] def reap[F[_], A, B](
      idleTimeAllowedInPoolNanos: FiniteDuration,
      kpVar: Ref[F, PoolMap[A, (B, ExitCase => F[Unit])]],
      onReaperException: Throwable => F[Unit]
  )(implicit F: Temporal[F]): F[Unit] = {
    // We are going to do non-referentially transparent things as we may be waiting for our modification to go through
    // which may change the state depending on when the modification block is running atomically at the moment
    def findStale(
        now: FiniteDuration,
        idleCount: Int,
        m: Map[A, PoolList[(B, ExitCase => F[Unit])]]
    ): (PoolMap[A, (B, ExitCase => F[Unit])], List[(A, (B, ExitCase => F[Unit]))]) = {
      val isNotStale: FiniteDuration => Boolean =
        time =>
          time + idleTimeAllowedInPoolNanos >= now // Time value is alright inside the KeyPool in nanos.

      // Probably a more idiomatic way to do this in scala
      // ([(key, PoolList resource)] -> [(key, PoolList resource)]) ->
      // ([resource] -> [resource]) ->
      // [(key, PoolList resource)] ->
      // (Map key (PoolList resource), [resource])
      @annotation.tailrec
      def findStale_(
          toKeep: List[(A, PoolList[(B, ExitCase => F[Unit])])] => List[
            (A, PoolList[(B, ExitCase => F[Unit])])
          ],
          toDestroy: List[(A, (B, ExitCase => F[Unit]))] => List[(A, (B, ExitCase => F[Unit]))],
          l: List[(A, PoolList[(B, ExitCase => F[Unit])])]
      ): (Map[A, PoolList[(B, ExitCase => F[Unit])]], List[(A, (B, ExitCase => F[Unit]))]) = {
        l match {
          case Nil => (toKeep(List.empty).toMap, toDestroy(List.empty))
          case (key, pList) :: rest =>
            // Can use span since we know everything will be ordered as the time is
            // when it is placed back into the pool.
            val (notStale, stale) = pList.toList.span(r => isNotStale(r._1))
            val toDestroy_ : List[(A, (B, ExitCase => F[Unit]))] => List[
              (A, (B, ExitCase => F[Unit]))
            ] = l => toDestroy(stale.map(t => key -> t._2) ++ l)
            val toKeep_ : List[(A, PoolList[(B, ExitCase => F[Unit])])] => List[
              (A, PoolList[(B, ExitCase => F[Unit])])
            ] =
              l =>
                PoolList.fromList(notStale) match {
                  case None => toKeep(l)
                  case Some(x) => toKeep((key, x) :: l)
                }
            findStale_(toKeep_, toDestroy_, rest)
        }
      }
      // May be able to use Span eventually
      val (toKeep, toDestroy) = findStale_(identity, identity, m.toList)
      val idleCount_ = idleCount - toDestroy.length
      (PoolMap.open(idleCount_, toKeep), toDestroy)
    }

    val sleep = Temporal[F].sleep(5.seconds).void

    // Wait 5 Seconds
    def loop: F[Unit] = Temporal[F].monotonic
      .flatMap { now =>
        kpVar.tryModify {
          case p @ PoolClosed() => (p, F.unit)
          case p @ PoolOpen(idleCount, m) =>
            if (m.isEmpty)
              (p, F.unit) // Not worth it to introduce deadlock concerns when hot loop is 5 seconds
            else {
              val (m_, toDestroy) = findStale(now, idleCount, m)
              (
                m_,
                // In this context, we're closing the resource due to it not being used for a while - hence a Succeeded exit case.
                toDestroy.traverse_(_._2._2(ExitCase.Succeeded)).attempt.flatMap {
                  case Left(t) => onReaperException(t)
                  // .handleErrorWith(t => F.delay(t.printStackTrace())) // CHEATING?
                  case Right(()) => F.unit
                }
              )
            }
        }
      }
      .flatMap {
        case Some(act) => act >> sleep >> loop
        case None => loop
      }

    loop
  }

  private[keypool] def state[F[_]: Functor, A, B](
      kpVar: Ref[F, PoolMap[A, (B, ExitCase => F[Unit])]]
  ): F[(Int, Map[A, Int])] =
    kpVar.get.map {
      case PoolClosed() =>
        (0, Map.empty)

      case PoolOpen(idleCount, m) =>
        val modified = m.map { case (k, pl) =>
          pl match {
            case One(_, _) => (k, 1)
            case Cons(_, length, _, _) => (k, length)
          }
        }
        (idleCount, modified)
    }

  private[keypool] def put[F[_]: Temporal, A, B](
      kp: KeyPoolConcrete[F, A, B],
      k: A,
      r: B,
      destroy: ExitCase => F[Unit]
  ): ExitCase => F[Unit] = {
    def addToList[Z](
        now: FiniteDuration,
        maxCount: Int,
        x: Z,
        l: PoolList[Z]
    ): (PoolList[Z], Option[Z]) =
      if (maxCount <= 1) (l, Some(x))
      else {
        l match {
          case l @ One(_, _) => (Cons(x, 2, now, l), None)
          case l @ Cons(_, currCount, _, _) =>
            if (maxCount > currCount) (Cons(x, currCount + 1, now, l), None)
            else (l, Some(x))
        }
      }
    def go(
        now: FiniteDuration,
        pc: PoolMap[A, (B, ExitCase => F[Unit])]
    ): (PoolMap[A, (B, ExitCase => F[Unit])], ExitCase => F[Unit]) =
      pc match {
        case p @ PoolClosed() => (p, destroy)
        case p @ PoolOpen(idleCount, m) =>
          if (idleCount > kp.kpMaxIdle) (p, destroy)
          else
            m.get(k) match {
              case None =>
                val cnt_ = idleCount + 1
                val m_ = PoolMap.open(cnt_, m + (k -> One((r, destroy), now)))
                (m_, Function.const(Applicative[F].unit))
              case Some(l) =>
                val (l_, mx) = addToList(now, kp.kpMaxPerKey(k), (r, destroy), l)
                val cnt_ = idleCount + mx.fold(1)(_ => 0)
                val m_ = PoolMap.open(cnt_, m + (k -> l_))
                (m_, mx.fold(Function.const(Applicative[F].unit))(_ => destroy))
            }
      }

    (exit: ExitCase) =>
      Clock[F].monotonic.flatMap { now =>
        kp.kpVar.modify(pm => go(now, pm)).flatMap(_(exit))
      }
  }

  private[keypool] def take[F[_]: Temporal, A, B](
      kp: KeyPoolConcrete[F, A, B],
      k: A
  ): Resource[F, Managed[F, B]] = {
    def go(
        pm: PoolMap[A, (B, ExitCase => F[Unit])]
    ): (PoolMap[A, (B, ExitCase => F[Unit])], Option[(B, ExitCase => F[Unit])]) =
      pm match {
        case p @ PoolClosed() => (p, None)
        case pOrig @ PoolOpen(idleCount, m) =>
          m.get(k) match {
            case None => (pOrig, None)
            case Some(One(a, _)) =>
              (PoolMap.open(idleCount - 1, m - k), Some(a))
            case Some(Cons(a, _, _, rest)) =>
              (PoolMap.open(idleCount - 1, m + (k -> rest)), Some(a))
          }
      }

    for {
      _ <- kp.kpMaxTotalSem.permit
      optR <- Resource.eval(kp.kpVar.modify(go))
      releasedState <- Resource.eval(Ref[F].of[Reusable](kp.kpDefaultReuseState))
      resource <- Resource.makeCaseFull[F, (B, ExitCase => F[Unit])] { poll =>
        optR.fold(poll(kp.kpRes(k).allocatedCase))(r => Applicative[F].pure(r))
      } { (resource, exitCase) =>
        for {
          reusable <- releasedState.get
          out <- reusable match {
            case Reusable.Reuse => put(kp, k, resource._1, resource._2).apply(exitCase).attempt.void
            case Reusable.DontReuse => resource._2(exitCase).attempt.void
          }
        } yield out
      }
    } yield new Managed(resource._1, optR.isDefined, releasedState)
  }

  final class Builder[F[_]: Temporal, A, B] private[keypool] (
      val kpRes: A => Resource[F, B],
      val kpDefaultReuseState: Reusable,
      val idleTimeAllowedInPool: Duration,
      val kpMaxPerKey: A => Int,
      val kpMaxIdle: Int,
      val kpMaxTotal: Int,
      val onReaperException: Throwable => F[Unit]
  ) {
    private def copy(
        kpRes: A => Resource[F, B] = this.kpRes,
        kpDefaultReuseState: Reusable = this.kpDefaultReuseState,
        idleTimeAllowedInPool: Duration = this.idleTimeAllowedInPool,
        kpMaxPerKey: A => Int = this.kpMaxPerKey,
        kpMaxIdle: Int = this.kpMaxIdle,
        kpMaxTotal: Int = this.kpMaxTotal,
        onReaperException: Throwable => F[Unit] = this.onReaperException
    ): Builder[F, A, B] = new Builder[F, A, B](
      kpRes,
      kpDefaultReuseState,
      idleTimeAllowedInPool,
      kpMaxPerKey,
      kpMaxIdle,
      kpMaxTotal,
      onReaperException
    )

    def doOnCreate(f: B => F[Unit]): Builder[F, A, B] =
      copy(kpRes = { (k: A) => this.kpRes(k).flatMap(v => Resource.eval(f(v).attempt.void.as(v))) })

    def doOnDestroy(f: B => F[Unit]): Builder[F, A, B] =
      copy(kpRes = { (k: A) =>
        this.kpRes(k).flatMap(v => Resource.make(Applicative[F].unit)(_ => f(v).attempt.void).as(v))
      })

    def withDefaultReuseState(defaultReuseState: Reusable): Builder[F, A, B] =
      copy(kpDefaultReuseState = defaultReuseState)

    def withIdleTimeAllowedInPool(duration: Duration): Builder[F, A, B] =
      copy(idleTimeAllowedInPool = duration)

    def withMaxPerKey(f: A => Int): Builder[F, A, B] =
      copy(kpMaxPerKey = f)

    def withMaxIdle(maxIdle: Int): Builder[F, A, B] =
      copy(kpMaxIdle = maxIdle)

    def withMaxTotal(total: Int): Builder[F, A, B] =
      copy(kpMaxTotal = total)

    def withOnReaperException(f: Throwable => F[Unit]): Builder[F, A, B] =
      copy(onReaperException = f)

    def build: Resource[F, KeyPool[F, A, B]] = {
      def keepRunning[Z](fa: F[Z]): F[Z] =
        fa.onError { case e => onReaperException(e) }.attempt >> keepRunning(fa)
      for {
        kpVar <- Resource.makeCase(
          Ref[F].of[PoolMap[A, (B, ExitCase => F[Unit])]](
            PoolMap.open(0, Map.empty[A, PoolList[(B, ExitCase => F[Unit])]])
          )
        )(KeyPool.destroy)
        kpMaxTotalSem <- Resource.eval(Semaphore[F](kpMaxTotal.toLong))
        _ <- idleTimeAllowedInPool match {
          case fd: FiniteDuration =>
            val nanos = 0.seconds.max(fd)
            keepRunning(KeyPool.reap(nanos, kpVar, onReaperException)).background.void
          case _ =>
            Applicative[Resource[F, *]].unit
        }
      } yield new KeyPool.KeyPoolConcrete(
        kpRes,
        kpDefaultReuseState,
        kpMaxPerKey,
        kpMaxIdle,
        kpMaxTotal,
        kpMaxTotalSem,
        kpVar
      )
    }

  }

  object Builder {
    def apply[F[_]: Temporal, A, B](
        res: A => Resource[F, B]
    ): Builder[F, A, B] = new Builder[F, A, B](
      res,
      Defaults.defaultReuseState,
      Defaults.idleTimeAllowedInPool,
      Defaults.maxPerKey,
      Defaults.maxIdle,
      Defaults.maxTotal,
      Defaults.onReaperException[F]
    )

    def apply[F[_]: Temporal, A, B](
        create: A => F[B],
        destroy: B => F[Unit]
    ): Builder[F, A, B] =
      apply(a => Resource.make(create(a))(destroy))

    private object Defaults {
      val defaultReuseState = Reusable.Reuse
      val idleTimeAllowedInPool = 30.seconds
      def maxPerKey[K](k: K): Int = Function.const(100)(k)
      val maxIdle = 100
      val maxTotal = 100
      def onReaperException[F[_]: Applicative] = { (t: Throwable) =>
        Function.const(Applicative[F].unit)(t)
      }
    }
  }
}
