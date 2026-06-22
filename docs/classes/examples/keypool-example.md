```scala 3
import cats.data.NonEmptyVector
import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.*
import fs2.Stream
import org.typelevel.keypool.*
import scala.concurrent.duration.*

// Runs the program on the given cluster.
def program[F[_]: {Temporal, Network}](
    cluster: NonEmptyVector[SocketAddress[Host]]
): F[Unit] =
  KeyPool
    // Creates a keyed pool that manages TCP connections to cluster nodes.
    .Builder: (key: Int) =>
      // Creates a socket connection to one of the cluster nodes.
      // Assumes that `0 < key && key < cluster.length` (see below).
      Network[F].client(cluster.getUnsafe(key))
    .withMaxTotal(20)                     // configures `maxTotal`
    .withMaxIdle(15)                      // configures `maxIdle`
    .withMaxPerKey(Function.const(10))    // configures `maxPerKey`
    .withIdleTimeAllowedInPool(5.seconds) // configures `idleTimeAllowedInPool`.
    .build
    .use: pool =>
      eventProducer
        // Processes events in parallel using pooled connections.
        .parEvalMapUnordered(10): req =>
          // Computes the key so that each request is dispatched to a specific
          // cluster node based on up to the first 3 characters of the request.
          val key = req.take(3).hash % cluster.length
          // Takes a connection from the pool.
          pool
            .take(key)
            .use: managed =>
              // Uses the connection to send a query to the server.
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
