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

import cats.effect.*
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite

import scala.concurrent.TimeoutException
import scala.concurrent.duration.*

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
      .withOnReaperException((_: Throwable) => IO.unit)
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
      .withOnReaperException((_: Throwable) => IO.unit)
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

  test("maxTotal limits checked-out resources") {
    val MaxTotal = 17
    val MaxIdle = MaxTotal + 7 // should be greater than MaxTotal, but it makes no difference
    val CheckOutTimeout = 5.second

    TestControl
      .executeEmbed {
        Pool
          .Builder(Resource.unit[IO])
          .withMaxTotal(MaxTotal)
          .withMaxIdle(MaxIdle)
          .withIdleTimeAllowedInPool(Duration.Inf)
          .build
          .use { pool =>
            for {
              // all MaxTotal resources are checked out at once, so the pool is exhausted
              exhausted <- pool.take.replicateA(MaxTotal).use { _ =>
                for {
                  failedAttempt <- pool.take.use_.timeout(CheckOutTimeout).attempt
                  state <- pool.state // no idle resources at this point - checked out only
                } yield (failedAttempt, state.total)
              }
              // all resources are returned to the pool
              state2 <- pool.state // all returned resources become idle
              // would deadlock if the permits were not released by the previous steps
              checkedOutAgain <- pool.take.replicateA(MaxTotal).use { managed =>
                pool.state.map(state => (managed.map(_.isReused), state.total))
              }
              state4 <- pool.state // all resources are returned again
            } yield (exhausted, state2.total, checkedOutAgain, state4.total)
          }
      }
      .map { case ((failedAttempt, state1), state2, (checkedOutAgain, state3), state4) =>
        assertMatches(failedAttempt) { case Left(ex: TimeoutException) =>
          ex.getMessage == CheckOutTimeout.toString()
        }
        // state1 shows no idle resources
        assertEquals(state1, 0)
        // state2 shows all MaxTotal resources idle
        assertEquals(state2, MaxTotal)
        // All MaxTotal idle resources are reused (none is newly created) and checked out again.
        assertEquals(checkedOutAgain, List.fill(MaxTotal)(true))
        // state3 shows no idle resources again
        assertEquals(state3, 0)
        // state4 shows that idle resources still do not exceed MaxTotal, although MaxIdle allows more
        assertEquals(state4, MaxTotal)
      }
  }

  test("state reports idle resources only") {
    TestControl
      .executeEmbed {
        Pool
          .Builder(Resource.unit[IO])
          .withIdleTimeAllowedInPool(Duration.Inf)
          .withMaxTotal(3)
          .build
          .use { pool =>
            for {
              // all three resources are checked out at once
              checkedOut <- List.fill(3)(pool.take).sequence.use { managed =>
                pool.state.map(state => (managed.map(_.isReused), state.total))
              }
              // all three resources are returned to the pool
              idle <- pool.state
            } yield (checkedOut, idle.total)
          }
      }
      .map { case (checkedOut, idle) =>
        // All three are newly created and checked out, so none is idle.
        assertEquals(checkedOut, (List.fill(3)(false), 0))
        // All three are idle once they are returned.
        assertEquals(idle, 3)
      }
  }

  test("requests served in FIFO order by default") {
    TestControl.executeEmbed {
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
            f1 <- reqAction(pool, ref, 1).start <* IO.sleep(1.milli)
            f2 <- reqAction(pool, ref, 2).start <* IO.sleep(1.milli)
            f3 <- reqAction(pool, ref, 3).start <* IO.sleep(1.milli)
            f4 <- reqAction(pool, ref, 4).start <* IO.sleep(1.milli)
            _ <- f1.cancel
            _ <- f2.join *> f3.join *> f4.join
            order <- ref.get
          } yield assertEquals(order, List(1, 2, 3, 4))
        }
    }
  }

  test("requests served in LIFO order if fairness is false") {
    TestControl.executeEmbed {
      Pool
        .Builder(
          Ref.of[IO, Int](1),
          nothing
        )
        .withMaxTotal(1)
        .withFairness(Fairness.Lifo)
        .build
        .use { pool =>
          for {
            ref <- IO.ref(List.empty[Int])
            f1 <- reqAction(pool, ref, 1).start <* IO.sleep(1.milli)
            f2 <- reqAction(pool, ref, 2).start <* IO.sleep(1.milli)
            f3 <- reqAction(pool, ref, 3).start <* IO.sleep(1.milli)
            f4 <- reqAction(pool, ref, 4).start <* IO.sleep(1.milli)
            _ <- f1.cancel
            _ <- f2.join *> f3.join *> f4.join
            order <- ref.get
          } yield assertEquals(order, List(1, 4, 3, 2))
        }
    }
  }

  test("do not lose permits when requests time out while another request releases") {
    val program = Pool
      .Builder(Ref.of[IO, Int](1), nothing)
      .withMaxTotal(1)
      .build
      .use { pool =>
        for {
          holder <- pool.take.use(_ => IO.sleep(1.second)).start
          _ <- IO.sleep(1.milli)
          // These timeouts fire at the same instant the holder releases. A request that is
          // cancelled while `release` hands it the permit takes the permit down with it.
          _ <- (1 to 8).toList.parTraverse_(_ => pool.take.use_.timeout(999.millis).attempt)
          _ <- holder.join
          // Test whether we can acquire a permit, fail with timeout if we can't (no permits available)
          _ <- pool.take.use_.timeout(1.minute)
        } yield ()
      }

    TestControl
      .executeEmbed(program)
      .adaptErr { case e: TimeoutException => new AssertionError(s"permit lost!", e) }
      .replicateA_(100) // repeat to increase the chance of hitting the race condition
  }

  private def reqAction(
      pool: Pool[IO, Ref[IO, Int]],
      ref: Ref[IO, List[Int]],
      id: Int
  ) =
    if (id == 1)
      pool.take.use(_ => ref.update(l => l :+ id) *> IO.never)
    else
      pool.take.use(_ => ref.update(l => l :+ id))

  private def nothing(ref: Ref[IO, Int]): IO[Unit] =
    ref.get.void
}
