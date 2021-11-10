package org.typelevel.keypool

import cats.syntax.all._
import cats.effect._
import scala.concurrent.duration._
import munit.CatsEffectSuite
import scala.concurrent.ExecutionContext

class PoolSpec extends CatsEffectSuite {

  override val munitExecutionContext: ExecutionContext = ExecutionContext.global

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

  // see https://github.com/typelevel/keypool/issues/291
  test("Borrowed Resource destroyed during cleanup") {
    val pool = Pool
      .Builder(
        IO.ref(true),
        (r: Ref[IO, Boolean]) => r.set(false)
      )
      .build

    def escapedResource(outerAwait: Deferred[IO, Unit]): IO[FiberIO[(Boolean, Boolean)]] =
      pool.use { p =>
        def job(innerAwait: Deferred[IO, Unit]): IO[(Boolean, Boolean)] =
          p.take.use { managed =>
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

  private def nothing(ref: Ref[IO, Int]): IO[Unit] =
    ref.get.void
}
