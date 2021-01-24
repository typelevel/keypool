package org.typelevel.keypool

import cats.syntax.all._
import cats.effect._
import scala.concurrent.duration._
import munit.CatsEffectSuite
import scala.concurrent.ExecutionContext

class KeypoolSpec extends CatsEffectSuite {

  override val munitExecutionContext: ExecutionContext = ExecutionContext.global

  test("Keep Resources marked to be kept") {
    def nothing(ref: Ref[IO, Int]): IO[Unit] = {
      ref.get.void
    }
    KeyPoolBuilder(
      {(i: Int) => Ref.of[IO, Int](i)},
      nothing
    ).withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(Duration.Inf)
      .withMaxPerKey(Function.const(10))
      .withMaxTotal(10)
      .withOnReaperException({(_: Throwable) => IO.unit})
      .build
      .use( k => 

      k.take(1)
        .use(_ => IO.unit) >>
      k.state.map(_._1)
    ).map(a => assert(a === 1))
  }

  test("Delete Resources marked to be deleted") {
    def nothing(ref: Ref[IO, Int]): IO[Unit] = {
      ref.get.void
    }
    KeyPoolBuilder(
      {(i: Int) => Ref.of[IO, Int](i)},
      nothing
    ).withDefaultReuseState(Reusable.DontReuse)
      .withIdleTimeAllowedInPool(Duration.Inf)
      .withMaxPerKey(Function.const(10))
      .withMaxTotal(10)
      .withOnReaperException({(_: Throwable) => IO.unit})
      .build
    .use( k =>

      k.take(1)
        .use(_ => IO.unit) >>
      k.state.map(_._1)
    ).map(a => assert(a === 0))
  }

  test("Delete Resource when pool is full"){
    def nothing(ref: Ref[IO, Int]): IO[Unit] = {
      ref.get.void
    }
    KeyPoolBuilder(
      {(i: Int) => Ref.of[IO, Int](i)},
      nothing
    ).withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(Duration.Inf)
      .withMaxPerKey(Function.const(1))
      .withMaxTotal(1)
      .withOnReaperException{(_: Throwable) => IO.unit}
      .build
      .use{ k =>

      val action = k.take(1)
      .use(_ => IO.unit)

      (action, action).parMapN{case (_, _) => IO.unit} >>
      k.state.map(_._1)
    }.map(a => assert(a === 1))
  }

  test("Used Resource Cleaned Up By Reaper") {
    def nothing(ref: Ref[IO, Int]): IO[Unit] = {
      ref.get.void
    }
    KeyPoolBuilder(
      {(i: Int) => Ref.of[IO, Int](i)},
      nothing
    ).withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(Duration.Zero)
      .withMaxPerKey(Function.const(1))
      .withMaxTotal(1)
      .withOnReaperException{(_: Throwable) => IO.unit}
      .build
      .use{ k =>


      val action = k.take(1)
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
    def nothing(ref: Ref[IO, Int]): IO[Unit] = {
      ref.get.void
    }

    KeyPoolBuilder(
      {(i: Int) => Ref.of[IO, Int](i)},
      nothing
    ).withDefaultReuseState(Reusable.Reuse)
      .withIdleTimeAllowedInPool(30.seconds)
      .withMaxPerKey(Function.const(1))
      .withMaxTotal(1)
      .withOnReaperException{(_: Throwable) => IO.unit}
      .build
      .use{ k =>

      val action = k.take(1)
      .use(_ => IO.unit)
      for {
        _ <- action
        init <- k.state.map(_._1)
        _ <- Temporal[IO].sleep(6.seconds)
        later <- k.state.map(_._1)
      } yield assert(init === 1 && later === 1)
    }
  }
}
