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
        0L,
        2,
        3,
        _ => IO.unit
    //     kpCreate: Key => F[Rezource],
    // kpDestroy: (Key, Rezource) => F[Unit],
    // kpDefaultReuseState: Reusable,
    // idleTimeAllowedInPoolNanos: Long,
    // kpMaxPerKey: Int,
    // kpMaxTotal: Int,
    // onReaperException: Throwable => F[Unit]
      ).use{kp =>
        List.fill(100)(()).parTraverse( _ =>
          kp.take(()).use( m =>
            m.resource.modify(i => (i+1, i+1)).flatMap(i =>  kp.state.flatMap(state => IO(println(s"Got: $i - State: $state")))) >> Timer[IO].sleep(1.nano)
          )
        )
      }.as(ExitCode.Success)
    // } yield ExitCode.Success
  }

}