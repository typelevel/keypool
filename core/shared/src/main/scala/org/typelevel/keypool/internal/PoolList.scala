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

import cats._
import scala.concurrent.duration.FiniteDuration

private[keypool] sealed trait PoolList[A] extends Product with Serializable {
  def toList: List[(FiniteDuration, A)] = this match {
    case One(a, created) => List((created, a))
    case Cons(a, _, created, tail) => (created, a) :: tail.toList
  }
}
private[keypool] object PoolList {
  def fromList[A](l: List[(FiniteDuration, A)]): Option[PoolList[A]] = l match {
    case Nil => None
    case (t, a) :: Nil => Some(One(a, t))
    case list =>
      def go(l: List[(FiniteDuration, A)]): (Int, PoolList[A]) = l match {
        case Nil => throw new Throwable("PoolList.fromList Nil")
        case (t, a) :: Nil => (2, One(a, t))
        case (t, a) :: rest =>
          val (i, rest_) = go(rest)
          val i_ = i + 1
          (i_, Cons(a, i, t, rest_))
      }
      Some(go(list)._2)
  }

  implicit val poolListFoldable: Foldable[PoolList] = new Foldable[PoolList] {
    def foldLeft[A, B](fa: PoolList[A], b: B)(f: (B, A) => B): B =
      Foldable[List].foldLeft(fa.toList.map(_._2), b)(f)
    def foldRight[A, B](fa: PoolList[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      Foldable[List].foldRight(fa.toList.map(_._2), lb)(f)
  }
}

private[keypool] final case class One[A](a: A, created: FiniteDuration) extends PoolList[A]
private[keypool] final case class Cons[A](
    a: A,
    length: Int,
    created: FiniteDuration,
    xs: PoolList[A]
) extends PoolList[A]
