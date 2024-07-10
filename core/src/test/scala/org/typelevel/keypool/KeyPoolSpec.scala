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
import cats.effect.testkit.TestControl

import scala.concurrent.duration._
import munit.CatsEffectSuite

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
      .withOnReaperException({ (_: Throwable) => IO.unit })
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
      .withOnReaperException({ (_: Throwable) => IO.unit })
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

  test("Do not allocate more resources than the maxTotal") {
    val MaxTotal = 10

    KeyPool
      .Builder(
        (i: Int) => Ref.of[IO, Int](i),
        nothing
      )
      .withMaxTotal(MaxTotal)
      .build
      .use { pool =>
        for {
          cdl <- CountDownLatch[IO](MaxTotal)
          allocated <- IO.parReplicateAN(MaxTotal)(
            MaxTotal,
            pool.take(1).use(_ => cdl.release >> IO.never).start
          )
          _ <- cdl.await // make sure the pool is exhausted
          attempt1 <- pool.take(1).use_.timeout(100.millis).attempt
          _ <- allocated.traverse(_.cancel)
          attempt2 <- pool.take(1).use_.timeout(100.millis).attempt
        } yield assert(attempt1.isLeft && attempt2.isRight)
      }
  }

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
