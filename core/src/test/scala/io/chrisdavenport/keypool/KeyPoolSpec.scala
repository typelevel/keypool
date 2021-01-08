package io.chrisdavenport.keypool

import org.specs2._
import cats.syntax.all._
import cats.effect._
import cats.effect.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.global
class KeypoolSpec extends mutable.Specification with ScalaCheck {

  "Keypool" should {
    "Keep Resources marked to be kept" in {
      def nothing(ref: Ref[IO, Int]): IO[Unit] = {
        ref.get.void
      }
      implicit val CS = IO.contextShift(global)
      implicit val T = IO.timer(global)
      KeyPoolBuilder(
        {i: Int => Ref.of[IO, Int](i)},
        nothing
      ).withDefaultReuseState(Reusable.Reuse)
        .withIdleTimeAllowedInPool(Duration.Inf)
        .withMaxPerKey(Function.const(10))
        .withMaxTotal(10)
        .withOnReaperException({_: Throwable => IO.unit})
        .build
        .use( k => 

        k.take(1)
          .use(_ => IO.unit) >>
        k.state.map(_._1)
      ).unsafeRunSync() must_=== (1)
    }

    "Delete Resources marked to be deleted" in {
      def nothing(ref: Ref[IO, Int]): IO[Unit] = {
        ref.get.void
      }
      implicit val CS = IO.contextShift(global)
      implicit val T = IO.timer(global)
      KeyPoolBuilder(
        {i: Int => Ref.of[IO, Int](i)},
        nothing
      ).withDefaultReuseState(Reusable.DontReuse)
        .withIdleTimeAllowedInPool(Duration.Inf)
        .withMaxPerKey(Function.const(10))
        .withMaxTotal(10)
        .withOnReaperException({_: Throwable => IO.unit})
        .build
      .use( k =>

        k.take(1)
          .use(_ => IO.unit) >>
        k.state.map(_._1)
      ).unsafeRunSync() must_=== (0)
    }

    "Delete Resource when pool is full" in {
      def nothing(ref: Ref[IO, Int]): IO[Unit] = {
        ref.get.void
      }
      implicit val CS = IO.contextShift(global)
      implicit val T = IO.timer(global)
      KeyPoolBuilder(
        {i: Int => Ref.of[IO, Int](i)},
        nothing
      ).withDefaultReuseState(Reusable.Reuse)
        .withIdleTimeAllowedInPool(Duration.Inf)
        .withMaxPerKey(Function.const(1))
        .withMaxTotal(1)
        .withOnReaperException{_: Throwable => IO.unit}
        .build
        .use{ k =>

        val action = k.take(1)
        .use(_ => IO.unit)

        (action, action).parMapN{case (_, _) => IO.unit} >>
        k.state.map(_._1)
      }.unsafeRunSync() must_=== (1)
    }

    "Used Resource Cleaned Up By Reaper" in {
      def nothing(ref: Ref[IO, Int]): IO[Unit] = {
        ref.get.void
      }
      implicit val CS = IO.contextShift(global)
      implicit val T = IO.timer(global)
      KeyPoolBuilder(
        {i: Int => Ref.of[IO, Int](i)},
        nothing
      ).withDefaultReuseState(Reusable.Reuse)
        .withIdleTimeAllowedInPool(Duration.Zero)
        .withMaxPerKey(Function.const(1))
        .withMaxTotal(1)
        .withOnReaperException{_: Throwable => IO.unit}
        .build
        .use{ k =>


        val action = k.take(1)
        .use(_ => IO.unit)
        for {
          _ <- action
          init <- k.state.map(_._1)
          _ <- Timer[IO].sleep(6.seconds)
          later <- k.state.map(_._1)
        } yield (init, later)
      }.unsafeRunSync() must_=== ((1, 0))
    }

    "Used Resource Not Cleaned Up if Idle Time has not expired" in {
      def nothing(ref: Ref[IO, Int]): IO[Unit] = {
        ref.get.void
      }
      implicit val CS = IO.contextShift(global)
      implicit val T = IO.timer(global)

      KeyPoolBuilder(
        {i: Int => Ref.of[IO, Int](i)},
        nothing
      ).withDefaultReuseState(Reusable.Reuse)
        .withIdleTimeAllowedInPool(30.seconds)
        .withMaxPerKey(Function.const(1))
        .withMaxTotal(1)
        .withOnReaperException{_: Throwable => IO.unit}
        .build
        .use{ k =>

        val action = k.take(1)
        .use(_ => IO.unit)
        for {
          _ <- action
          init <- k.state.map(_._1)
          _ <- Timer[IO].sleep(6.seconds)
          later <- k.state.map(_._1)
        } yield (init, later)
      }.unsafeRunSync() must_=== ((1, 1))
    }
  }
}
