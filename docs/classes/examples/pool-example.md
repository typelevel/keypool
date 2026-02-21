```scala 3
import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.*
import fs2.Stream
import org.typelevel.keypool.*
import scala.concurrent.duration.*

// Runs the program on the given address.
def program[F[_]: {Temporal, Network}](address: SocketAddress[Host]): F[Unit] =
  Pool
    // Creates a pool that manages TCP connections to the given address.
    .Builder(Network[F].client(address))
    .withMaxTotal(20)                     // configures `maxTotal`
    .withMaxIdle(15)                      // configures `maxIdle`
    .withIdleTimeAllowedInPool(5.seconds) // configures `idleTimeAllowedInPool`
    .build
    .use: pool =>
      eventProducer
        // Processes events in parallel using pooled connections.
        .parEvalMapUnordered(10): req =>
          // Takes a connection from the pool.
          pool.take.use: managed =>
            // Uses the connection to make a query to the server.
            serverQuery(managed.value, req).flatMap: res =>
              // Marks the connection as non‑reusable if the response is incorrect.
              managed.canBeReused
                .set(Reusable.DontReuse)
                .unlessA(isCorrect(res))
        .compile
        .drain

// Produces a stream of events.
def eventProducer[F[_]]: Stream[F, String] = ???

// Sends a request over the given socket connection and returns the server’s response.
def serverQuery[F[_]](socket: Socket[F], req: String): F[String] = ???

// Validates the response.
def isCorrect(res: String): Boolean = ???
```
