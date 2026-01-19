# Pool

`Pool` is a simple specialization of [`KeyPool`] that manages reusable resources without
partitioning by key. It enforces global limits on idle and total resources and hands out resources
as [`Managed`] values which include a per-resource [`Reusable`] flag so callers can indicate whether
the value should be returned to the pool or destroyed. A background reaper evicts idle resources
after a configurable timeout. `Pool` instances are constructed with [`Pool.Builder`] which allows
configuration of creation/destruction callbacks, reuse policy, eviction timing, limits,
and [fairness][Fairness].

See also [`KeyPool`] – a full-featured multi-key generalization that partitions resources by a user
provided key type and exposes per-key limits and accounting.

## Pool.Builder

`Pool.Builder` constructs a configured `Pool` instance. It takes the managed resource
factory; it also allows to tune pool policies and limits.

The table below enumerates all the available configuration parameters.

| Parameter Name                | Parameter Type | Default Value    | Description                                                                                                                                                                                              |
|-------------------------------|----------------|------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `defaultReuseState`           | [`Reusable`]   | `Reusable.Reuse` | Default [`Reusable`] state applied when resources are returned to the pool; [`Managed.canBeReused`][`Managed`] can override it per acquisition.                                                          |
| `durationBetweenEvictionRuns` | `Duration`     | 5 seconds        | Interval between successive reaper eviction runs; a negative or infinite value disables the reaper. **Note**: value **0** would make the reaper run nonstop, which can be inefficient and CPU-consuming. |
| `fairness`                    | [`Fairness`]   | `Fairness.Fifo`  | Fairness policy for acquiring permits from the global semaphore.                                                                                                                                         |
| `idleTimeAllowedInPool`       | `Duration`     | 30 seconds       | How long an idle resource is allowed to remain in the pool before the reaper considers it for eviction.                                                                                                  |
| `maxIdle`                     | `Int`          | 100              | Cap on the idle resources tracked across all keys.                                                                                                                                                       |
| `maxTotal`                    | `Int`          | 100              | Global limit on the total number of concurrent resources the pool will hold.                                                                                                                             |

The builder also allows to configure the following lifecycle callbacks.

| Callback Registrar      | Description                                                                                                           |
|-------------------------|-----------------------------------------------------------------------------------------------------------------------|
| `doOnCreate`            | Register a callback that runs after a new item is created; callback failures are ignored.                             |
| `doOnDestroy`           | Register a callback that runs when an item is about to be destroyed; callback failures are ignored.                   |
| `withOnReaperException` | Register a callback that is invoked when the background reaper observes a `Throwable`; callback failures are ignored. |

**Note**: this builder delegates to [`KeyPool.Builder`] with key type `Unit` and uses `maxTotal` for
the `maxPerKey` parameter.

## Example

This example demonstrates how to use `Pool` to manage reusable TCP socket connections to a single
server node. It constructs a configured `Pool` from a socket `Resource` and runs a `Stream` that
processes events in parallel using pooled connections.

@:include(examples/pool-example.md)

The implementations of `eventProducer`, `serverQuery`, and `isCorrect` are intentionally omitted
because they are not relevant to the example.
