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

class TakeCancelSpec extends CatsEffectSuite {

  test("Cancelling take does not orphan a pooled resource") {
    val racers = 64
    val bursts = 400
    val sink = new java.util.concurrent.atomic.AtomicLong(0L)

    def spin(n: Int): Unit = {
      var i = 0
      var acc = 0L
      while (i < n) {
        acc += System.nanoTime()
        i += 1
      }
      sink.set(acc)
    }

    for {
      allocated <- Ref[IO].of(0)
      destroyed <- Ref[IO].of(0)
      state <- KeyPool
        .Builder(
          (_: Unit) => allocated.updateAndGet(_ + 1),
          (_: Int) => destroyed.update(_ + 1)
        )
        .withIdleTimeAllowedInPool(Duration.Inf)
        .withMaxPerKey(Function.const(racers * 2))
        .withMaxTotal(racers * 2)
        .build
        .use { pool =>
          // Seed idle resources, then race take against cancellation at varying delays.
          // Reseed after each burst to keep exercising reuse. A resource lost between removal
          // and finalizer registration remains allocated but is neither pooled nor destroyed.
          val seed = List.fill(racers)(()).traverse(_ => pool.take(())).use_

          def burst(b: Int) = List.range(0, racers).parTraverse_ { i =>
            pool.take(()).use_.start.flatMap { f =>
              IO.delay(spin((b * 7 + i * 9) % 600)) *> f.cancel
            }
          }

          for {
            _ <- seed
            _ <- List.range(0, bursts).traverse_(b => burst(b) *> seed)
            st <- pool.state
            a <- allocated.get
            d <- destroyed.get
          } yield (a - d - st._1, st._1, st._2.values.sum)
        }
        .timeoutTo(
          10.seconds,
          IO.raiseError(new AssertionError("timed out, a permit was probably lost"))
        )
    } yield {
      val (leaked, idle, perKey) = state
      assertEquals(idle, perKey, "pool idle count disagrees with its per-key totals")
      assertEquals(leaked, 0, s"$leaked resources were neither destroyed nor pooled")
    }
  }
}
