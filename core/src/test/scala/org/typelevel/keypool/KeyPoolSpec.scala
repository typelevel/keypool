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

class KeyPoolSpec extends CatsEffectSuite {
  test("Keep Resources marked to be kept") {
    KeyPool
      .Builder(
        (i: Int) => Ref.of[IO, Int](i),
        nothing
      )
      .withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(Duration.Inf)
      .withMaxPerKey(Function.const(10))
      .withMaxTotal(10)
      .withOnReaperException((_: Throwable) => IO.unit)
      .build
      .use(k =>
        k.take(1)
          .use(_ => IO.unit) >>
          k.state.map(_._1)
      )
      .map(a => assert(a === 1))
  }

  test("Delete Resources marked to be deleted") {
    KeyPool
      .Builder(
        (i: Int) => Ref.of[IO, Int](i),
        nothing
      )
      .withDefaultReuseState(Reusable.DontReuse)
      .withIdleTimeAllowedInPool(Duration.Inf)
      .withMaxPerKey(Function.const(10))
      .withMaxTotal(10)
      .withOnReaperException((_: Throwable) => IO.unit)
      .build
      .use(k =>
        k.take(1)
          .use(_ => IO.unit) >>
          k.state.map(_._1)
      )
      .map(a => assert(a === 0))
  }

  test("Delete Resource when pool is full") {
    KeyPool
      .Builder(
        (i: Int) => Ref.of[IO, Int](i),
        nothing
      )
      .withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(Duration.Inf)
      .withMaxPerKey(Function.const(1))
      .withMaxTotal(1)
      .withOnReaperException((_: Throwable) => IO.unit)
      .build
      .use { k =>
        val action = k
          .take(1)
          .use(_ => IO.unit)

        (action, action).parMapN { case (_, _) => IO.unit } >>
          k.state.map(_._1)
      }
      .map(a => assert(a === 1))
  }

  test("Used Resource Cleaned Up By Reaper") {
    def program(ref: Ref[IO, Int]) =
      TestControl.executeEmbed(
        KeyPool
          .Builder(
            (i: Int) => Ref.of[IO, Int](i),
            nothing
          )
          .withDefaultReuseState(Reusable.Reuse)
          .withIdleTimeAllowedInPool(Duration.Zero)
          .withMaxPerKey(Function.const(1))
          .withMaxTotal(1)
          .withOnReaperException(_ => IO.unit)
          .build
          .use(k =>
            for {
              _ <- k
                .take(1)
                .use(_ => IO.unit)
              _ <- k.state.map(_._1).flatMap(i => ref.set(i))
              _ <- Temporal[IO].sleep(6.second)
              _ <- k.state.map(_._1).flatMap(i => ref.set(i))
            } yield ()
          )
      )

    for {
      ref <- Ref.of[IO, Int](-1)
      _ <- program(ref)
      result <- ref.get
    } yield assert(result === 0)
  }

  test("Used Resource Not Cleaned Up if Idle Time has not expired") {
    KeyPool
      .Builder(
        (i: Int) => Ref.of[IO, Int](i),
        nothing
      )
      .withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(30.seconds)
      .withMaxPerKey(Function.const(1))
      .withMaxTotal(1)
      .withOnReaperException((_: Throwable) => IO.unit)
      .build
      .use { k =>
        val action = k
          .take(1)
          .use(_ => IO.unit)
        for {
          _ <- action
          init <- k.state.map(_._1)
          _ <- Temporal[IO].sleep(6.seconds)
          later <- k.state.map(_._1)
        } yield assert(init === 1 && later === 1)
      }
  }

  test("Used resource not cleaned up if idle time expired but eviction hasn't run") {
    KeyPool
      .Builder(
        (i: Int) => Ref.of[IO, Int](i),
        nothing
      )
      .withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(5.seconds)
      .withDurationBetweenEvictionRuns(7.seconds)
      .withMaxPerKey(Function.const(1))
      .withMaxTotal(1)
      .withOnReaperException((_: Throwable) => IO.unit)
      .build
      .use { k =>
        val action = k
          .take(1)
          .use(_ => IO.unit)
        for {
          _ <- action
          init <- k.state.map(_._1)
          _ <- Temporal[IO].sleep(6.seconds)
          later <- k.state.map(_._1)
        } yield assert(init === 1 && later === 1)
      }
  }

  test("Used resource not cleaned up if idle time expired but eviction is disabled") {
    KeyPool
      .Builder(
        (i: Int) => Ref.of[IO, Int](i),
        nothing
      )
      .withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(5.seconds)
      .withDurationBetweenEvictionRuns(-1.seconds)
      .withMaxPerKey(Function.const(1))
      .withMaxTotal(1)
      .withOnReaperException((_: Throwable) => IO.unit)
      .build
      .use { k =>
        val action = k
          .take(1)
          .use(_ => IO.unit)
        for {
          _ <- action
          init <- k.state.map(_._1)
          _ <- Temporal[IO].sleep(6.seconds)
          later <- k.state.map(_._1)
        } yield assert(init === 1 && later === 1)
      }
  }

  test("maxTotal limits checked-out resources but not idle ones") {
    val MaxTotal = 17
    val CountB = 11 // should be less than MaxTotal
    val MaxIdle = MaxTotal + CountB // should be greater than MaxTotal
    val CheckOutTimeout = 5.second

    TestControl
      .executeEmbed {
        KeyPool
          .Builder((_: String) => Resource.unit[IO])
          .withMaxTotal(MaxTotal)
          .withMaxIdle(MaxIdle)
          .withIdleTimeAllowedInPool(Duration.Inf)
          .build
          .use { pool =>
            for {
              // all MaxTotal resources are checked out at once, so the pool is exhausted
              exhausted <- pool.take("A").replicateA(MaxTotal).use { _ =>
                for {
                  failedAttempt <- pool.take("A").use_.timeout(CheckOutTimeout).attempt
                  state <- pool.state // no idle resources at this point - checked out only
                } yield (failedAttempt, state)
              }
              state2 <- pool.state // all returned resources become idle
              // would deadlock if the permits were not released by the previous step
              state3 <- pool.take("B").replicateA(CountB).use { _ =>
                // idle resources of "A" can't be reused for "B"
                pool.state // "A" resources are still idle, "B" ones are checked out
              }
              state4 <- pool.state // idle resources of both keys together exceed MaxTotal
            } yield (exhausted, state2, state3, state4)
          }
      }
      .map { case ((failedAttempt, state1), state2, state3, state4) =>
        assertMatches(failedAttempt) { case Left(ex: TimeoutException) =>
          ex.getMessage == CheckOutTimeout.toString()
        }
        // state1 shows no idle resources
        assertEquals(state1, (0, Map.empty[String, Int]))
        // state2 shows all MaxTotal resources of "A" idle
        assertEquals(state2, (MaxTotal, Map("A" -> MaxTotal)))
        // state3 shows that only the idle resources of "A" are counted
        assertEquals(state3, (MaxTotal, Map("A" -> MaxTotal)))
        // state4 shows MaxTotal idle resources of "A" plus CountB of "B": idle resources are not
        // limited by MaxTotal
        assertEquals(state4, (MaxTotal + CountB, Map("A" -> MaxTotal, "B" -> CountB)))
      }
  }

  test("maxPerKey limits idle resources only") {
    TestControl
      .executeEmbed {
        KeyPool
          .Builder((_: String) => Resource.unit[IO])
          .withIdleTimeAllowedInPool(Duration.Inf)
          .withMaxPerKey(Function.const(2))
          .withMaxTotal(10)
          .build
          .use { pool =>
            // "A" is checked out once, "B" twice, "C" three times and "D" four times
            val keys = "A" :: List.fill(2)("B") ::: List.fill(3)("C") ::: List.fill(4)("D")

            for {
              // maxPerKey does not limit checked-out resources: all ten are taken at once, including
              // three for "C" and four for "D" (otherwise the last take would block and TestControl
              // would report a deadlock)
              checkedOut <- keys.traverse(pool.take).use { managed =>
                pool.state.map(state => (managed.map(_.isReused), state))
              }
              // maxPerKey limits idle resources: "C" and "D" keep only two resources each once they
              // are returned
              idle <- pool.state
            } yield (checkedOut, idle)
          }
      }
      .map { case (checkedOut, idle) =>
        // All ten are newly created and checked out, so none is idle (`state` counts idle ones only).
        assertEquals(checkedOut, (List.fill(10)(false), (0, Map.empty[String, Int])))
        // Seven of the ten returned resources stay idle: "C" and "D" are capped at two by `maxPerKey`.
        assertEquals(idle, (7, Map("A" -> 1, "B" -> 2, "C" -> 2, "D" -> 2)))
      }
  }

  test("requests served in FIFO order by default") {
    TestControl.executeEmbed {
      KeyPool
        .Builder(
          (i: Int) => Ref.of[IO, Int](i),
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
      KeyPool
        .Builder(
          (i: Int) => Ref.of[IO, Int](i),
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

  private def reqAction(
      pool: KeyPool[IO, Int, Ref[IO, Int]],
      ref: Ref[IO, List[Int]],
      id: Int
  ) =
    if (id == 1)
      pool.take(1).use(_ => ref.update(l => l :+ id) *> IO.never)
    else
      pool.take(1).use(_ => ref.update(l => l :+ id))

  private def nothing(ref: Ref[IO, Int]): IO[Unit] =
    ref.get.void

  test("Acquiring from pool is cancelable") {
    TestControl.executeEmbed {
      KeyPool.Builder((_: Unit) => Resource.eval(IO.never[Unit])).build.use { pool =>
        pool.take(()).use_.timeoutTo(1.second, IO.unit)
      }
    }
  }

}
