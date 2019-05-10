package io.chrisdavenport.keypool


import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import scala.concurrent.duration._

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    // for {
      // KeyPool.createFullBounded(
        KeyPool.create(
        {_: Unit => Ref[IO].of(0)},
        {(_: Unit, r: Ref[IO, Int]) => r.get.flatMap{i => IO(println(s"Shutdown with $i"))}},
        Reuse,
        10000000000L,
        Function.const(10),
        10,
        _ => IO.unit
    //     kpCreate: Key => F[Rezource],
    // kpDestroy: (Key, Rezource) => F[Unit],
    // kpDefaultReuseState: Reusable,
    // idleTimeAllowedInPoolNanos: Long,
    // kpMaxPerKey: Int,
    // kpMaxTotal: Int,
    // onReaperException: Throwable => F[Unit]
      ).use{kp =>
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
