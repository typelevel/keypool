package io.chrisdavenport.keypool

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._
import scala.concurrent.duration._
import io.chrisdavenport.keypool.internal._


/**
 * This pools internal guarantees are that the max number of values
 * are in the pool at any time, not maximum number of operations.
 * To do the latter application level bounds should be used.
 *
 * A background reaper thread is kept alive for the length of the key pools life.
 *
 * When resources are taken from the pool they are received as a [[Managed]].
 * This [[Managed]] has a Ref to a [[Reusable]] which indicates whether or not the pool
 * can reuse the resource.
 *
 * (I have a commented create function introducing this functionality here, so it
 * may be introduced later.)
 */
final class KeyPool[F[_]: Sync: Clock, Key, Rezource] private[keypool] (
  private[keypool] val kpCreate: Key => F[Rezource],
  private[keypool] val kpDestroy: (Key, Rezource) => F[Unit],
  private[keypool] val kpDefaultReuseState: Reusable,
  private[keypool] val kpMaxPerKey: Key => Int,
  private[keypool] val kpMaxTotal: Int,
  private[keypool] val kpVar: Ref[F, PoolMap[Key, Rezource]]
){

  /**
   * Take a [[Managed]] from the Pool. For the lifetime of this
   * resource this is exclusively available to this key.
   *
   * At the end of the resource lifetime the state of the resource
   * controls whether it is submitted back to the pool or removed.
   */
  def take(k: Key): Resource[F, Managed[F, Rezource]] =
    KeyPool.take(this, k)

  /**
   * Place a Resource into the pool, following the rules for addition
   * for this pool. The Resouce may be shutdown if the pool is already
   * full in either perKey or maxTotal dimensions.
   */
  def put(k: Key, r: Rezource): F[Unit] = KeyPool.put(this, k, r)

  /**
   * The current state of the pool.
   *
   * The left value is the total number of resources currently in
   * the pool, and the right is a map of how many resources exist
   * for each key.
   */
  def state: F[(Int, Map[Key, Int])] = KeyPool.state(kpVar)

  /**
   * An action to take with the Key, Resource pair directly after it
   * has been initially created
   */
  def doOnCreate(f: (Key, Rezource) => F[Unit]): KeyPool[F, Key, Rezource] =
    new KeyPool(
      {k: Key => kpCreate(k).flatMap(v => f(k, v).attempt.void.as(v))},
      kpDestroy,
      kpDefaultReuseState,
      kpMaxPerKey,
      kpMaxTotal,
      kpVar
    )

  /**
   * An action to take with the key, resource pair directly prior to
   * permanently destroying the resource
   */
  def doOnDestroy(f: (Key, Rezource) => F[Unit]): KeyPool[F, Key, Rezource] =
    new KeyPool(
      kpCreate,
      { (k: Key, r: Rezource) => f(k, r).attempt.void >> kpDestroy(k, r)},
      kpDefaultReuseState,
      kpMaxPerKey,
      kpMaxTotal,
      kpVar
    )
}

object KeyPool{

  /**
   * Pool Bounded Interaction.
   * Limits Number of Values in The Pool Not Total Using the Pools Resources.
   */
  def create[F[_]: Concurrent: Timer, Key, Rezource](
    kpCreate: Key => F[Rezource],
    kpDestroy: (Key, Rezource) => F[Unit],
    kpDefaultReuseState: Reusable,
    idleTimeAllowedInPoolNanos: Long,
    kpMaxPerKey: Key => Int,
    kpMaxTotal: Int,
    onReaperException: Throwable => F[Unit]
  ): Resource[F, KeyPool[F, Key, Rezource]] = {
    def keepRunning[A](fa: F[A]): F[A] =
      fa.onError{ case e => onReaperException(e)}.attempt >> keepRunning(fa)
    for {
      kpVar <- Resource.make(
        Ref[F].of[PoolMap[Key, Rezource]](PoolMap.open(0, Map.empty[Key, PoolList[Rezource]]))
      )(kpVar => destroy(kpDestroy, kpVar))
      _ <- Resource.make(Concurrent[F].start(keepRunning(reap(kpDestroy, idleTimeAllowedInPoolNanos, kpVar))))(_.cancel)
    } yield new KeyPool(
      kpCreate,
      kpDestroy,
      kpDefaultReuseState,
      kpMaxPerKey,
      kpMaxTotal,
      kpVar
    )
  }

  // Not Solid Yet
  // def createAppBounded[F[_]: Concurrent: Timer, Key, Rezource](
  //   kpCreate: Key => F[Rezource],
  //   kpDestroy: (Key, Rezource) => F[Unit],
  //   kpDefaultReuseState: Reusable,
  //   idleTimeAllowedInPoolNanos: Long,
  //   kpMaxPerKey: Int,
  //   kpMaxTotal: Int,
  //   onReaperException: Throwable => F[Unit]
  // ): Resource[F, KeyPool[F, Key, Rezource]] = for {
  //   total <- Resource.liftF(Semaphore[F](kpMaxTotal.toLong))
  //   ref <- Resource.liftF(Ref[F].of(Map.empty[Key, Semaphore[F]]))
  //   kpCreate_ = {k: Key =>
  //     ref.modify(m =>
  //       (m, m.get(k).fold(
  //         Semaphore[F](kpMaxPerKey.toLong)
  //         .flatMap(s =>
  //           ref.modify(m => m.get(k) match {
  //             case Some(s) => (m, s.acquire)
  //             case None => (m + (k -> s), s.acquire)
  //           })
  //         )
  //       )(_.acquire.pure[F]))
  //     ).flatten >> total.acquire >> kpCreate(k)
  //   }
  //   kpDestroy_ = {(k: Key, r: Rezource) =>
  //     total.release >> ref.get.flatMap(m => m(k).release) >> kpDestroy(k, r)
  //   }
  //   _ <- Resource.make(Concurrent[F].start{
  //     def reportTotal: F[Unit] = total.count.flatMap{a => Sync[F].delay(println(s"Total Count - $a"))} >> Timer[F].sleep(1.second) >> reportTotal
  //     reportTotal
  //   })(_.cancel)
  //   out <- create(
  //     kpCreate_,
  //     kpDestroy_,
  //     kpDefaultReuseState,
  //     idleTimeAllowedInPoolNanos,
  //     kpMaxPerKey,
  //     kpMaxTotal,
  //     onReaperException
  //   )
  // } yield out

  // Internal Helpers

  /**
   * Make a 'KeyPool' inactive and destroy all idle resources.
   */
  private[keypool] def destroy[F[_]: MonadError[?[_], Throwable], Key, Rezource](
    kpDestroy: (Key, Rezource) => F[Unit],
    kpVar: Ref[F, PoolMap[Key, Rezource]]
  ): F[Unit] = for {
    m <- kpVar.getAndSet(PoolMap.closed[Key, Rezource])
    _ <- m match {
      case PoolClosed() => Applicative[F].unit
      case PoolOpen(_, m2) =>
        m2.toList.traverse_{ case (k, pl) =>
          pl.toList
            .traverse_{ case (_, r) =>
              kpDestroy(k, r).attempt.void
            }
        }
    }
      //m.traverse_{ r: Rezource => kpDestroy(r).handleErrorWith(_ => Applicative[F].unit)}
  } yield ()

  /**
   * Run a reaper thread, which will destroy old resources. It will
   * stop running once our pool switches to PoolClosed.
   *
   */
  private[keypool] def reap[F[_]: Concurrent: Timer, Key, Rezource](
    destroy: (Key, Rezource) => F[Unit],
    idleTimeAllowedInPool: Long,
    kpVar: Ref[F, PoolMap[Key, Rezource]]
  ): F[Unit] = {
    // We are going to do non-referentially tranpsarent things as we may be waiting for our modification to go through
    // which may change the state depending on when the modification block is running atomically at the moment
    def findStale(idleCount: Int, m: Map[Key, PoolList[Rezource]]): (PoolMap[Key, Rezource], List[(Key, Rezource)]) = {
      val now = System.nanoTime
      val isNotStale: Long => Boolean = time => time + idleTimeAllowedInPool >= now // Time value is alright inside the KeyPool in nanos.

      // Probably a more idiomatic way to do this in scala
      //([(key, PoolList resource)] -> [(key, PoolList resource)]) ->
      // ([resource] -> [resource]) ->
      // [(key, PoolList resource)] ->
      // (Map key (PoolList resource), [resource])
      def findStale_(
        toKeep: List[(Key, PoolList[Rezource])] => List[(Key, PoolList[Rezource])],
        toDestroy: List[(Key, Rezource)] => List[(Key, Rezource)],
        l: List[(Key, PoolList[Rezource])]
      ): (Map[Key, PoolList[Rezource]], List[(Key, Rezource)]) = {
        l match {
          case Nil => (toKeep(List.empty).toMap, toDestroy(List.empty))
          case (key, pList) :: rest =>
            // Can use span since we know everything will be ordered as the time is
            // when it is placed back into the pool.
            val (notStale, stale) = pList.toList.span(r => isNotStale(r._1))
            val toDestroy_ : List[(Key, Rezource)] => List[(Key, Rezource)] = l =>
            toDestroy((stale.map(t => (key -> t._2)) ++ l))
            val toKeep_ : List[(Key, PoolList[Rezource])] => List[(Key, PoolList[Rezource])] = l =>
              PoolList.fromList(notStale) match {
                case None => toKeep(l)
                case Some(x) => toKeep((key, x):: l)
              }
            findStale_(toKeep_, toDestroy_, rest)
        }
      }
      // May be able to use Span eventually
      val (toKeep, toDestroy) = findStale_(identity, identity, m.toList)
      val idleCount_ = idleCount - toDestroy.length
      (PoolMap.open(idleCount_, toKeep), toDestroy)
    }
    // Wait 5 Seconds
    def loop: F[Unit] = for {
      _ <- Timer[F].sleep(5.seconds)
      _ <- {
        kpVar.modify{
          case p@PoolClosed() => (p, Applicative[F].pure(()))
          case p@PoolOpen(idleCount, m) =>
            if (m.isEmpty) (p, loop) // Not worth it to introduce deadlock concerns when hot loop is 5 seconds
            else {
              val (m_, toDestroy) = findStale(idleCount,m)
              (m_, toDestroy.traverse_(r => destroy(r._1, r._2).attempt.void))
            }
        }
      }.flatten
    } yield ()

    loop
  }

  private[keypool] def state[F[_]: Functor, Key, Rezource](kpVar: Ref[F, PoolMap[Key, Rezource]]): F[(Int, Map[Key, Int])] =
    kpVar.get.map(pm =>
      pm match {
        case PoolClosed() => (0, Map.empty)
        case PoolOpen(idleCount, m) =>
          val modified = m.map{ case (k, pl) =>
              pl match {
                case One(_, _) => (k, 1)
                case Cons(_, length, _, _) => (k, length)
              }
          }.toMap
          (idleCount, modified)
      }
    )

  private[keypool] def put[F[_]: Sync: Clock, Key, Rezource](kp: KeyPool[F, Key, Rezource], k: Key, r: Rezource): F[Unit] = {
    def addToList[A](now: Long, maxCount: Int, x: A, l: PoolList[A]): (PoolList[A], Option[A]) =
      if (maxCount <= 1) (l, Some(x))
      else {
        l match {
          case l@One(_, _) => (Cons(x, 2, now, l), None)
          case l@Cons(_, currCount, _, _) =>
            if (maxCount > currCount) (Cons(x, currCount + 1, now, l), None)
            else (l, Some(x))
        }
      }
    def go(now: Long, pc: PoolMap[Key, Rezource]): (PoolMap[Key, Rezource], F[Unit]) = pc match {
      case p@PoolClosed() => (p, kp.kpDestroy(k, r))
      case p@PoolOpen(idleCount, m) =>
        if (idleCount > kp.kpMaxTotal) (p, kp.kpDestroy(k, r))
        else m.get(k) match {
          case None =>
            val cnt_ = idleCount + 1
            val m_ = PoolMap.open(cnt_, m + (k -> One(r, now)))
            (m_, Applicative[F].pure(()))
          case Some(l) =>
            val (l_, mx) = addToList(now, kp.kpMaxPerKey(k), r, l)
            val cnt_ = idleCount + mx.fold(1)(_ => 0)
            val m_ = PoolMap.open(cnt_, m + (k -> l_))
            (m_, mx.fold(Applicative[F].unit)(r => kp.kpDestroy(k, r)))
        }
    }

    Clock[F].monotonic(NANOSECONDS).flatMap{ now =>
      kp.kpVar.modify(pm => go(now,pm)).flatten
    }
  }

  private[keypool] def take[F[_]: Sync: Clock, Key, Rezource](kp: KeyPool[F, Key, Rezource], k: Key): Resource[F, Managed[F, Rezource]] = {
    def go(pm: PoolMap[Key, Rezource]): (PoolMap[Key, Rezource], Option[Rezource]) = pm match {
      case p@PoolClosed() => (p, None)
      case pOrig@PoolOpen(idleCount, m) =>
        m.get(k) match {
          case None => (pOrig, None)
          case Some(One(a, _)) =>
            (PoolMap.open(idleCount - 1, m - (k)), Some(a))
          case Some(Cons(a, _, _, rest)) =>
            (PoolMap.open(idleCount - 1, m + (k -> rest)), Some(a))
        }
    }

    for {
      optR <- Resource.liftF(kp.kpVar.modify(go))
      releasedState <- Resource.liftF(Ref[F].of[Reusable](kp.kpDefaultReuseState))
      resource <- Resource.make(optR.fold(kp.kpCreate(k))(r => Sync[F].pure(r))){resource =>
        for {
        reusable <- releasedState.get
        out <- reusable match {
          case Reuse => put(kp, k, resource).attempt.void
          case DontReuse => kp.kpDestroy(k, resource).attempt.void
        }
        } yield out
      }
    } yield new Managed(resource, optR.isDefined, releasedState)
  }
}
