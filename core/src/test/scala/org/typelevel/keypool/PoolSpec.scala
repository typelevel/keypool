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

import cats.syntax.all._
import cats.effect._
import cats.effect.std.CountDownLatch
import scala.concurrent.duration._
import munit.CatsEffectSuite

class PoolSpec extends CatsEffectSuite {

  test("Keep Resources marked to be kept") {
    Pool
      .Builder(
        Ref.of[IO, Int](1),
        nothing
      )
      .withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(Duration.Inf)
      .withMaxTotal(10)
      .withOnReaperException({ (_: Throwable) => IO.unit })
      .build
      .use(pool =>
        pool.take
          .use(_ => IO.unit) >>
          pool.state
      )
      .map(a => assert(a.total === 1))
  }

  test("Delete Resources marked to be deleted") {
    Pool
      .Builder(
        Ref.of[IO, Int](1),
        nothing
      )
      .withDefaultReuseState(Reusable.DontReuse)
      .withIdleTimeAllowedInPool(Duration.Inf)
      .withMaxTotal(10)
      .withOnReaperException({ (_: Throwable) => IO.unit })
      .build
      .use(pool =>
        pool.take
          .use(_ => IO.unit) >>
          pool.state
      )
      .map(a => assert(a.total === 0))
  }

  test("Delete Resource when pool is full") {
    Pool
      .Builder(
        Ref.of[IO, Int](1),
        nothing
      )
      .withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(Duration.Inf)
      .withMaxTotal(1)
      .withOnReaperException((_: Throwable) => IO.unit)
      .build
      .use { pool =>
        val action = pool.take
          .use(_ => IO.unit)

        (action, action).parMapN { case (_, _) => IO.unit } >>
          pool.state
      }
      .map(a => assert(a.total === 1))
  }

  test("Used Resource Cleaned Up By Reaper") {
    Pool
      .Builder(
        Ref.of[IO, Int](1),
        nothing
      )
      .withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(Duration.Zero)
      .withMaxTotal(1)
      .withOnReaperException((_: Throwable) => IO.unit)
      .build
      .use { pool =>
        val action = pool.take
          .use(_ => IO.unit)
        for {
          _ <- action
          init <- pool.state
          _ <- Temporal[IO].sleep(6.seconds)
          later <- pool.state
        } yield assert(init.total === 1 && later.total === 0)
      }
  }

  test("Used Resource Not Cleaned Up if Idle Time has not expired") {
    Pool
      .Builder(
        Ref.of[IO, Int](1),
        nothing
      )
      .withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(30.seconds)
      .withMaxTotal(1)
      .withOnReaperException((_: Throwable) => IO.unit)
      .build
      .use { pool =>
        val action = pool.take
          .use(_ => IO.unit)
        for {
          _ <- action
          init <- pool.state
          _ <- Temporal[IO].sleep(6.seconds)
          later <- pool.state
        } yield assert(init.total === 1 && later.total === 1)
      }
  }

  test("Used resource not cleaned up if idle time expired but eviction hasn't run") {
    Pool
      .Builder(
        Ref.of[IO, Int](1),
        nothing
      )
      .withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(5.seconds)
      .withDurationBetweenEvictionRuns(7.seconds)
      .withMaxTotal(1)
      .withOnReaperException((_: Throwable) => IO.unit)
      .build
      .use { pool =>
        val action = pool.take
          .use(_ => IO.unit)
        for {
          _ <- action
          init <- pool.state
          _ <- Temporal[IO].sleep(6.seconds)
          later <- pool.state
        } yield assert(init.total === 1 && later.total === 1)
      }
  }

  test("Used resource not cleaned up if idle time expired but eviction is disabled") {
    Pool
      .Builder(
        Ref.of[IO, Int](1),
        nothing
      )
      .withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(5.seconds)
      .withDurationBetweenEvictionRuns(-1.seconds)
      .withMaxTotal(1)
      .withOnReaperException((_: Throwable) => IO.unit)
      .build
      .use { pool =>
        val action = pool.take
          .use(_ => IO.unit)
        for {
          _ <- action
          init <- pool.state
          _ <- Temporal[IO].sleep(6.seconds)
          later <- pool.state
        } yield assert(init.total === 1 && later.total === 1)
      }
  }

  test("Do not allocate more resources than the maxTotal") {
    val MaxTotal = 10

    Pool
      .Builder(
        Ref.of[IO, Int](1),
        nothing
      )
      .withMaxTotal(MaxTotal)
      .build
      .use { pool =>
        for {
          cdl <- CountDownLatch[IO](MaxTotal)
          allocated <- IO.parReplicateAN(MaxTotal)(
            MaxTotal,
            pool.take.use(_ => cdl.release >> IO.never).start
          )
          _ <- cdl.await // make sure the pool is exhausted
          attempt1 <- pool.take.use_.timeout(100.millis).attempt
          _ <- allocated.traverse(_.cancel)
          attempt2 <- pool.take.use_.timeout(100.millis).attempt
        } yield assert(attempt1.isLeft && attempt2.isRight)
      }
  }

  test("requests served in FIFO order by default") {
    Pool
      .Builder(
        Ref.of[IO, Int](1),
        nothing
      )
      .withMaxTotal(1)
      .build
      .use { pool =>
        for {
          ref <- IO.ref(List.empty[Int])
          gate <- CountDownLatch[IO](4)
          f1 <- reqAction(pool, ref, gate, 1).start <* IO.sleep(20.milli)
          _ <- reqAction(pool, ref, gate, 2).start *> IO.sleep(20.milli)
          _ <- reqAction(pool, ref, gate, 3).start *> IO.sleep(20.milli)
          _ <- reqAction(pool, ref, gate, 4).start *> IO.sleep(20.milli)
          _ <- f1.cancel
          _ <- gate.await
          order <- ref.get
        } yield assertEquals(order, List(1, 2, 3, 4))
      }
  }

  test("requests served in LIFO order if fairness is false") {
    Pool
      .Builder(
        Ref.of[IO, Int](1),
        nothing
      )
      .withMaxTotal(1)
      .withFairness(false)
      .build
      .use { pool =>
        for {
          ref <- IO.ref(List.empty[Int])
          gate <- CountDownLatch[IO](4)
          f1 <- reqAction(pool, ref, gate, 1).start <* IO.sleep(20.milli)
          _ <- reqAction(pool, ref, gate, 2).start *> IO.sleep(20.milli)
          _ <- reqAction(pool, ref, gate, 3).start *> IO.sleep(20.milli)
          _ <- reqAction(pool, ref, gate, 4).start *> IO.sleep(20.milli)
          _ <- f1.cancel
          _ <- gate.await
          order <- ref.get
        } yield assertEquals(order, List(1, 4, 3, 2))
      }
  }

  private def reqAction(
      pool: Pool[IO, Ref[IO, Int]],
      ref: Ref[IO, List[Int]],
      gate: CountDownLatch[IO],
      id: Int
  ) =
    if (id == 1)
      pool.take.use(_ => ref.update(l => l :+ id) *> gate.release *> IO.never)
    else
      pool.take.use(_ => ref.update(l => l :+ id) *> gate.release)

  private def nothing(ref: Ref[IO, Int]): IO[Unit] =
    ref.get.void
}
