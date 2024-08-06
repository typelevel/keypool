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

package org.typelevel.keypool.internal

import cats.syntax.all._
import cats.effect.kernel.syntax.all._
import cats.effect.kernel._
import scala.collection.immutable.{Queue => ScalaQueue}
import scala.annotation.nowarn

sealed trait Fairness
case object Lifo extends Fairness
case object Fifo extends Fairness

// Derived from cats-effect MiniSemaphore
// https://github.com/typelevel/cats-effect/blob/v3.5.4/kernel/shared/src/main/scala/cats/effect/kernel/MiniSemaphore.scala#L29
private[keypool] abstract class RequestSemaphore[F[_]] {
  def permit: Resource[F, Unit]
}

private[keypool] object RequestSemaphore {
  private trait BackingQueue[F[_], A] {
    def cleanup(fa: F[A], elem: A): F[A]
    def offer(fa: F[A], elem: A): F[A]
    def take(fa: F[A]): (F[A], A)
    def nonEmpty(fa: F[A]): Boolean
  }

  private implicit def listQueue[A <: AnyRef]: BackingQueue[List, A] = new BackingQueue[List, A] {
    def cleanup(fa: List[A], elem: A) = fa.filterNot(_ eq elem)
    def offer(fa: List[A], elem: A) = elem :: fa
    def take(fa: List[A]) = (fa.tail, fa.head)
    def nonEmpty(fa: List[A]) = fa.nonEmpty
  }

  private implicit def queueQueue[A <: AnyRef]: BackingQueue[ScalaQueue, A] =
    new BackingQueue[ScalaQueue, A] {
      def cleanup(fa: ScalaQueue[A], elem: A) = fa.filterNot(_ eq elem)
      def offer(fa: ScalaQueue[A], elem: A) = fa :+ elem
      def take(fa: ScalaQueue[A]) = (fa.tail, fa.head)
      def nonEmpty(fa: ScalaQueue[A]) = fa.nonEmpty
    }

  private case class State[F[_], A](waiting: F[A], permits: Int)(implicit
      @nowarn ev: BackingQueue[F, A]
  )

  def apply[F[_]](fairness: Fairness, n: Int)(implicit
      F: GenConcurrent[F, _]
  ): F[RequestSemaphore[F]] = {
    require(n >= 0, s"n must be nonnegative, was: $n")

    fairness match {
      case Fifo => F.ref(State(ScalaQueue[Deferred[F, Unit]](), n)).map(semaphore(_))
      case Lifo => F.ref(State(List.empty[Deferred[F, Unit]], n)).map(semaphore(_))
    }
  }

  private def semaphore[F[_], G[_]](
      state: Ref[F, State[G, Deferred[F, Unit]]]
  )(implicit F: GenConcurrent[F, _], B: BackingQueue[G, Deferred[F, Unit]]): RequestSemaphore[F] = {
    new RequestSemaphore[F] {
      private def acquire: F[Unit] =
        F.uncancelable { poll =>
          F.deferred[Unit].flatMap { wait =>
            val cleanup = state.update { case s @ State(waiting, permits) =>
              if (B.nonEmpty(waiting))
                State(B.cleanup(waiting, wait), permits)
              else s
            }

            state.modify { case State(waiting, permits) =>
              if (permits == 0) {
                State(B.offer(waiting, wait), permits) -> poll(wait.get).onCancel(cleanup)
              } else
                State(waiting, permits - 1) -> F.unit
            }.flatten
          }
        }

      private def release: F[Unit] =
        state.flatModify { case State(waiting, permits) =>
          if (B.nonEmpty(waiting)) {
            val (rest, next) = B.take(waiting)
            State(rest, permits) -> next.complete(()).void
          } else
            State(waiting, permits + 1) -> F.unit
        }

      def permit: Resource[F, Unit] =
        Resource.makeFull((poll: Poll[F]) => poll(acquire))(_ => release)

      // def withPermit[A](fa: F[A]): F[A] = F.uncancelable { poll =>
      //   poll(acquire) >> poll(fa).guarantee(release)
      // }
    }
  }
}
