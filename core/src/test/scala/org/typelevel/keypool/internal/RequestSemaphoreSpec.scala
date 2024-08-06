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

import munit.CatsEffectSuite
import cats.effect._
import cats.effect.testkit.TestControl
import scala.concurrent.duration._

import org.typelevel.keypool.Fairness._

class RequestSemaphoreSpec extends CatsEffectSuite {

  test("throw if permits is negative") {
    interceptIO[IllegalArgumentException](IO.defer(RequestSemaphore[IO](Fifo, -1)))
  }

  test("acquire permit and execute action") {
    val b = RequestSemaphore[IO](Fifo, 1).flatMap { sem =>
      sem.permit.surround(IO.pure(true))
    }
    assertIOBoolean(b)
  }

  test("block if no permits are available") {
    val time = for {
      sem <- RequestSemaphore[IO](Fifo, 1)
      _ <- sem.permit.surround(IO.sleep(3.second)).start *> IO.sleep(10.milli)
      t <- sem.permit.surround(IO.unit).timed
    } yield t
    assertIOBoolean(time.map { case (t, _) => t > 2.seconds })
  }

  test("release the permit if action errors") {
    val b = for {
      sem <- RequestSemaphore[IO](Fifo, 1)
      _ <- sem.permit.surround(IO.raiseError(new Exception("honk"))).attempt
      b <- sem.permit.surround(IO.pure(true))
    } yield b
    assertIOBoolean(b)
  }

  test("release the permit if action cancelled") {
    val b = for {
      sem <- RequestSemaphore[IO](Fifo, 1)
      fiber <- sem.permit.surround(IO.never).start
      _ <- IO.sleep(10.milli)
      _ <- fiber.cancel
      b <- sem.permit.surround(IO.pure(true))
    } yield b
    assertIOBoolean(b)
  }

  test("cancel an action while waiting for permit") {
    val r = for {
      sem <- RequestSemaphore[IO](Fifo, 1)
      ref <- IO.ref(0)
      f1 <- sem.permit.surround(IO.never).start
      _ <- IO.sleep(10.milli)
      f2 <- sem.permit.surround(ref.update(_ + 1)).start
      _ <- IO.sleep(10.milli)
      _ <- f2.cancel // cancel before acquiring
      _ <- f1.cancel
      r <- ref.get
    } yield r
    assertIO(r, 0)
  }

  test("acquire permits in FIFO order") {
    TestControl.executeEmbed {
      val r = for {
        sem <- RequestSemaphore[IO](Fifo, 1)
        ref <- IO.ref(List.empty[Int])
        f1 <- action(sem, ref, 1).start <* IO.sleep(1.milli)
        f2 <- action(sem, ref, 2).start <* IO.sleep(1.milli)
        f3 <- action(sem, ref, 3).start <* IO.sleep(1.milli)
        f4 <- action(sem, ref, 4).start <* IO.sleep(1.milli)
        _ <- f1.cancel
        _ <- f2.join *> f3.join *> f4.join
        xs <- ref.get
      } yield xs

      assertIO(r, List(1, 2, 3, 4))
    }
  }

  test("acquire permits in LIFO order") {
    TestControl.executeEmbed {
      val r = for {
        sem <- RequestSemaphore[IO](Lifo, 1)
        ref <- IO.ref(List.empty[Int])
        f1 <- action(sem, ref, 1).start <* IO.sleep(1.milli)
        f2 <- action(sem, ref, 2).start <* IO.sleep(1.milli)
        f3 <- action(sem, ref, 3).start <* IO.sleep(1.milli)
        f4 <- action(sem, ref, 4).start <* IO.sleep(1.milli)
        _ <- f1.cancel
        _ <- f2.join *> f3.join *> f4.join
        xs <- ref.get
      } yield xs

      assertIO(r, List(1, 4, 3, 2))
    }
  }

  private def action(
      sem: RequestSemaphore[IO],
      ref: Ref[IO, List[Int]],
      id: Int
  ): IO[Unit] =
    if (id == 1)
      sem.permit.surround(ref.update(xs => xs :+ id) *> IO.never)
    else
      sem.permit.surround(ref.update(xs => xs :+ id))
}
