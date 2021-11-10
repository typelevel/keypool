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
import cats.effect.kernel.Unique
import cats.syntax.all._

private[keypool] sealed trait PoolMap[Key, Rezource] extends Product with Serializable {
  def foldLeft[B](b: B)(f: (B, Rezource) => B): B = this match {
    case PoolClosed() => b
    case PoolOpen(_, _, m) => m.foldLeft(b) { case (b, (_, pl)) => pl.foldLeft(b)(f) }
  }
  def foldRight[B](lb: Eval[B])(f: (Rezource, Eval[B]) => Eval[B]): Eval[B] = this match {
    case PoolClosed() => lb
    case PoolOpen(_, _, m) =>
      Foldable.iterateRight(m.values, lb) { case (pl, b) => pl.foldRight(b)(f) }
  }
}
private[keypool] object PoolMap {
  implicit def poolMapFoldable[K]: Foldable[PoolMap[K, *]] = new Foldable[PoolMap[K, *]] {
    def foldLeft[A, B](fa: PoolMap[K, A], b: B)(f: (B, A) => B): B = fa.foldLeft(b)(f)
    def foldRight[A, B](fa: PoolMap[K, A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      fa.foldRight(lb)(f)
  }
  def closed[K, R]: PoolMap[K, R] = PoolClosed()
  def open[K, R](n: Int, borrowed: Map[Unique.Token, R], m: Map[K, PoolList[R]]): PoolMap[K, R] =
    PoolOpen(n, borrowed, m)
}

private[keypool] final case class PoolClosed[Key, Rezource]() extends PoolMap[Key, Rezource]
private[keypool] final case class PoolOpen[Key, Rezource](
    idleCount: Int,
    borrowed: Map[Unique.Token, Rezource],
    m: Map[Key, PoolList[Rezource]]
) extends PoolMap[Key, Rezource]
