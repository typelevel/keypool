package io.chrisdavenport.keypool


import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import scala.concurrent.duration._

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    // for {
      // KeyPool.createFullBounded(
        KeyPoolBuilder(
          {_: Unit => Ref[IO].of(0)},
          {r: Ref[IO, Int] => r.get.flatMap{i => IO(println(s"Shutdown with $i"))}}
        ).withDefaultReuseState(Reusable.Reuse)
          .withIdleTimeAllowedInPool(1.day)
          .withMaxPerKey(Function.const(10)(_))
          .withMaxTotal(10)
          .build
        .use{kp =>
        // Deferred[IO, Unit].flatMap{d => 
          kp.take(()).use(_ => IO.unit) >> {
            def action : IO[Unit] = kp.state.flatMap{s => IO(println(s"State $s")) >> {
              if(s._1 === 0) IO.unit
              else Timer[IO].sleep(1.second) >> action
            }}
            action
          }
        // }

        // Semaphore[IO](10).flatMap{ s=> 
        // List.fill(100)(()).parTraverse( _ =>
        //   s.withPermit(kp.take(()).use( m =>
        //     m.resource.modify(i => (i+1, i+1)).flatMap(i =>  kp.state.flatMap(state => IO(println(s"Got: $i - State: $state"))))
        //   ))
        // )} 
        // >> {
        // for {
        //   now <- Clock[IO].monotonic(NANOSECONDS)
        //   now2 <- IO(System.nanoTime)
        //   out <- IO(println(s"Now - $now, Now2 - $now2"))
        // } yield out
      // }
      }.as(ExitCode.Success)

    // val s = IO(IO(println)).flatten
    // } yield ExitCode.Success
  }

}
