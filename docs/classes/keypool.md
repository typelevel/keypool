# KeyPool

`KeyPool` manages reusable resources partitioned by a user-provided key of arbitrary type. It allows
to enforce both per-key and global limits on idle and total resources. It hands out resources as
[`Managed`] values which include a per-resource [`Reusable`] flag so callers can indicate whether
the value should be returned to the pool or destroyed. A background reaper evicts idle resources
after a configurable timeout. `KeyPool` instances are constructed with [KeyPool.Builder] that allows
to configure creation/destruction callbacks, reuse policy, eviction timing, limits,
and [fairness][Fairness].

See also [`Pool`] – a convenience, single-key specialization of `KeyPool` that does not
partition resources by key and exposes simpler APIs.

## KeyPool.Builder

`KeyPool.Builder` constructs a configured `KeyPool` instance. It takes the managed resource
factory; it also allows to tune pool policies and limits.

The table below enumerates all the available configuration parameters.

| Parameter Name                | Parameter Type | Default Value    | Description                                                                                                                                                                                              |
|-------------------------------|----------------|------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `defaultReuseState`           | [`Reusable`]   | `Reusable.Reuse` | Default [`Reusable`] state applied when resources are returned to the pool; [`Managed.canBeReused`][`Managed`] can override it per acquisition.                                                          |
| `durationBetweenEvictionRuns` | `Duration`     | 5 seconds        | Interval between successive reaper eviction runs; a negative or infinite value disables the reaper. **Note**: value **0** would make the reaper run nonstop, which can be inefficient and CPU-consuming. |
| `fairness`                    | [`Fairness`]   | `Fairness.Fifo`  | Fairness policy for acquiring permits from the global semaphore.                                                                                                                                         |
| `idleTimeAllowedInPool`       | `Duration`     | 30 seconds       | How long an idle resource is allowed to remain in the pool before the reaper considers it for eviction.                                                                                                  |
| `maxIdle`                     | `Int`          | 100              | Cap on the idle resources tracked across all keys.                                                                                                                                                       |
| `maxPerKey`                   | `A => Int`     | 100              | Maximum number of idle resources kept per key `A`. **Note**: it only limits idle resources per key; it does not limit how many resources can be checked out concurrently.                                |
| `maxTotal`                    | `Int`          | 100              | Global limit on the total number of concurrent resources the pool will hold.                                                                                                                             |

The builder also allows to configure the following lifecycle callbacks.

| Callback Registrar      | Description                                                                                                           |
|-------------------------|-----------------------------------------------------------------------------------------------------------------------|
| `doOnCreate`            | Register a callback that runs after a new item is created; callback failures are ignored.                             |
| `doOnDestroy`           | Register a callback that runs when an item is about to be destroyed; callback failures are ignored.                   |
| `withOnReaperException` | Register a callback that is invoked when the background reaper observes a `Throwable`; callback failures are ignored. |

## Example

TBD
