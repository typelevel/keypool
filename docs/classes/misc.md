# Miscellaneous

This page describes small core types used across the **keypool** implementation.

## Fairness

Represents a policy that controls the ordering of pending requests. It's defined as a coproduct type
with two cases:

- `Fairness.Fifo` – first-in, first-out ordering. Pending requests are served in arrival order. This
  avoids starvation and provides predictable, fair ordering across different callers or tenants.
  Prefer this option when fairness, predictability, and avoiding starvation across clients is
  important.

  **Example**: a public-facing service where you want older requests to be satisfied before newer
  ones, so no client can be starved by others.

- `Fairness.Lifo` – last-in, first-out ordering. The most recently queued request is served first.
  This can be beneficial in bursty workloads where recently released resources are still "warm".
  Prefer this option when you expect a lot of short-lived churn and want to favor recently-returned
  resources for better locality and potential latency improvements.

  **Example**: HTTP keep-alive (persistent TCP connections) – reusing the most recently returned
  connection avoids the latency of establishing a new TCP/TLS session and is less likely to fail
  because of idle timeouts.

Use [`Pool.Builder`] or [`KeyPool.Builder`] to set the policy when creating a pool instance. The
default is `Fairness.Fifo`.

## Managed

`Managed` represents an acquired resource together with reuse-tracking and a reference that
determines whether it is returned to the pool. `Managed` consists of the following fields:

| Field Name    | Field Type         | Description                                                                                                                                            |
|---------------|--------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| `value`       | `A`                | The underlying resource of type `A` held by this `Managed`                                                                                             |
| `isReused`    | `Boolean`          | Indicates whether the resource was taken from the pool (`true`) or newly created (`false`).                                                            |
| `canBeReused` | `Ref[F, Reusable]` | A mutable reference controlling reuse: when the `Managed` is released this `Ref` determines whether the resource is returned to the pool or shut down. |

### Reusable

`Reusable` indicates whether a resource should be reused or shut down. It's defined as a coproduct
type with two cases:

- `Reusable.Reuse` – the returned resource is kept in the pool and may be reused.
- `Reusable.DontReuse` – the returned resource must not be reused and should be shut down.
