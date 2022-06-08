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
import scala.concurrent.duration._
import munit.CatsEffectSuite
import scala.concurrent.ExecutionContext

class KeypoolSpec extends CatsEffectSuite {

  override val munitExecutionContext: ExecutionContext = ExecutionContext.global

  test("Keep Resources marked to be kept") {
    def nothing(ref: Ref[IO, Int]): IO[Unit] =
      ref.get.void
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
    def nothing(ref: Ref[IO, Int]): IO[Unit] =
      ref.get.void
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
    def nothing(ref: Ref[IO, Int]): IO[Unit] =
      ref.get.void
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
    def nothing(ref: Ref[IO, Int]): IO[Unit] =
      ref.get.void
    KeyPool
      .Builder(
        (i: Int) => Ref.of[IO, Int](i),
        nothing
      )
      .withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(Duration.Zero)
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
        } yield assert(init === 1 && later === 0)
      }
  }

  test("Used Resource Not Cleaned Up if Idle Time has not expired") {
    def nothing(ref: Ref[IO, Int]): IO[Unit] =
      ref.get.void

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

  // see https://github.com/typelevel/keypool/issues/291
  test("Borrowed Resource destroyed during cleanup") {
    val pool = KeyPool
      .Builder(
        (_: Int) => IO.ref(true),
        (r: Ref[IO, Boolean]) => r.set(false)
      )
      .build

    def escapedResource(outerAwait: Deferred[IO, Unit]): IO[FiberIO[(Boolean, Boolean)]] =
      pool.use { p =>
        def job(innerAwait: Deferred[IO, Unit]): IO[(Boolean, Boolean)] =
          p.take(1).use { managed =>
            val value = managed.value

            for {
              status1 <- value.get
              _ <- innerAwait.complete(())
              _ <- outerAwait.get
              status2 <- value.get
            } yield (status1, status2)
          }

        for {
          await <- IO.deferred[Unit]
          fiber <- job(await).start
          _ <- await.get // make sure the first part happens inside of the open pool
        } yield fiber
      }

    for {
      await <- IO.deferred[Unit]
      fiber <- escapedResource(await)
      _ <- await.complete(()) // pool is closed and a fiber can continue its execution
      outcome <- fiber.join
      result <- outcome.embedNever
      (status1, status2) = result
    } yield assert(status1 === true && status2 === false)
  }
}
