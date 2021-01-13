package org.typelevel.keypool.internal

import cats._
import cats.syntax.all._

private[keypool] sealed trait PoolMap[Key, Rezource] extends Product with Serializable {
  def foldLeft[B](b: B)(f: (B, Rezource) => B): B = this match {
    case PoolClosed() => b
    case PoolOpen(_, m) => m.foldLeft(b){ case (b, (_, pl)) => pl.foldLeft(b)(f)}
  }
  def foldRight[B](lb: Eval[B])(f: (Rezource, Eval[B]) => Eval[B]): Eval[B] = this match {
    case PoolClosed() => lb
    case PoolOpen(_, m) => Foldable.iterateRight(m.values, lb){ case (pl, b) => pl.foldRight(b)(f)}
  }
}
private[keypool] object PoolMap {
  implicit def poolMapFoldable[K]: Foldable[PoolMap[K, *]] = new Foldable[PoolMap[K, *]]{
    def foldLeft[A, B](fa: PoolMap[K, A],b: B)(f: (B, A) => B): B = fa.foldLeft(b)(f)
    def foldRight[A, B](fa: PoolMap[K, A],lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      fa.foldRight(lb)(f)
  }
  def closed[K, R]: PoolMap[K, R] = PoolClosed()
  def open[K, R](n: Int, m: Map[K, PoolList[R]]): PoolMap[K, R] = PoolOpen(n, m)
}

private[keypool] final case class PoolClosed[Key, Rezource]() extends PoolMap[Key, Rezource]
private[keypool] final case class PoolOpen[Key, Rezource](idleCount: Int, m: Map[Key, PoolList[Rezource]]) extends PoolMap[Key, Rezource]
