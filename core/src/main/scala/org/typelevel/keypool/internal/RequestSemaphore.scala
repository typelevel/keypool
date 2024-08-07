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

import org.typelevel.keypool.Fairness

/**
 * RequestSemaphore moderates access to pooled connections by setting the number of permits
 * available to the total number of connections. This is a custom semaphore implementation that only
 * provides the `permit` operation. Additionally it takes a [[Fairness]] parameter, used to toggle
 * the order in which requests acquire a permit.
 *
 * Derived from cats-effect MiniSemaphore
 * https://github.com/typelevel/cats-effect/blob/v3.5.4/kernel/shared/src/main/scala/cats/effect/kernel/MiniSemaphore.scala#L29
 */
private[keypool] abstract class RequestSemaphore[F[_]] {
  def permit: Resource[F, Unit]
}

private[keypool] object RequestSemaphore {
  private trait BackingQueue[CC[_], A] {
    def cleanup(fa: CC[A], elem: A): CC[A]
    def offer(fa: CC[A], elem: A): CC[A]
    def take(fa: CC[A]): (CC[A], A)
    def nonEmpty(fa: CC[A]): Boolean
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

  private case class State[CC[_], A](waiting: CC[A], permits: Int)(implicit
      @nowarn ev: BackingQueue[CC, A]
  )

  def apply[F[_]](fairness: Fairness, numPermits: Int)(implicit
      F: GenConcurrent[F, _]
  ): F[RequestSemaphore[F]] = {
    require(numPermits >= 0, s"numPermits must be nonnegative, was: $numPermits")

    fairness match {
      case Fairness.Fifo =>
        F.ref(State(ScalaQueue.empty[Deferred[F, Unit]], numPermits)).map(semaphore(_))
      case Fairness.Lifo =>
        F.ref(State(List.empty[Deferred[F, Unit]], numPermits)).map(semaphore(_))
    }
  }

  private def semaphore[F[_], CC[_]](
      state: Ref[F, State[CC, Deferred[F, Unit]]]
  )(implicit
      F: GenConcurrent[F, _],
      B: BackingQueue[CC, Deferred[F, Unit]]
  ): RequestSemaphore[F] = {
    new RequestSemaphore[F] {
      private def acquire: F[Unit] =
        F.deferred[Unit].flatMap { wait =>
          val cleanup = state.update { case s @ State(waiting, permits) =>
            if (B.nonEmpty(waiting))
              State(B.cleanup(waiting, wait), permits)
            else s
          }

          state.flatModifyFull { case (poll, State(waiting, permits)) =>
            if (permits == 0) {
              State(B.offer(waiting, wait), permits) -> poll(wait.get).onCancel(cleanup)
            } else
              State(waiting, permits - 1) -> F.unit
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
    }
  }
}
