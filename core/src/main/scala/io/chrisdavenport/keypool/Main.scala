// package io.chrisdavenport.keypool
// import cats.implicits._
// import cats.effect._
// import cats.effect.concurrent._
// import scala.concurrent.duration._

// object Main extends IOApp {

//   def run(args: List[String]): IO[ExitCode] = {
//     // for {
//       // kp <- KeyPool.createFullBounded(
//         KeyPool.createFullBounded(
//         {_: Unit => Ref[IO].of(0)},
//         {(_: Unit, _: Ref[IO, Int]) => IO.unit},
//         Reuse,
//         Long.MaxValue,
//         1,
//         1,
//         _ => IO.unit
//     //     kpCreate: Key => F[Rezource],
//     // kpDestroy: (Key, Rezource) => F[Unit],
//     // kpDefaultReuseState: Reusable,
//     // idleTimeAllowedInPoolNanos: Long,
//     // kpMaxPerKey: Int,
//     // kpMaxTotal: Int,
//     // onReaperException: Throwable => F[Unit]
//       ).use{kp => 
//         List.fill(100)(()).parTraverse( _ => 
//           kp.take(()).use( m => 
//             m.resource.modify(i => (i+1, i+1)).flatMap(i =>  IO(println(i)))
//           )
//         )
//       }.as(ExitCode.Success)
//     // } yield ExitCode.Success
//   }

// }