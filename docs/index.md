# keypool - A Keyed Pool for Scala

[![Maven Central](https://img.shields.io/maven-central/v/org.typelevel/keypool_2.13)](https://central.sonatype.com/artifact/org.typelevel/keypool_2.13)

## Overview

**keypool** provides a lightweight keyed resource pool abstraction for controlling concurrent access
to reusable resources. It limits concurrent acquisitions and reclaims resources to prevent leaks.
Idle resources are partitioned by key, and deterministic lifecycle semantics enable predictable
behavior under load.

The library is designed for integration within the [Typelevel][typelevel] ecosystem. It is
implemented on top of [Cats Effect][cats-effect] type classes using **Polymorphic Effects** (a.k.a.
**Tagless Final**).

The library provides the following primary implementations:

- [`Pool`] – a simple specialization that manages reusable resources without partitioning by
  key. Use it when you need a single shared pool of identical resources, such as a pool of
  connections to a single endpoint when connection partitioning isn't required.
- [`KeyPool`] – a general-purpose pool that partitions resources by a user-defined key. Use
  it when idle resources must not be shared across keys, for example:
    - In cluster-based deployments, to keep a separate set of reusable connections per node, so
      that a request for a node always reuses a connection to that node.
    - In multi-tenant systems, to keep reusable connections partitioned per tenant (for example,
      when each tenant has its own credentials). **Note**: the pool does not limit how many
      connections each tenant can have checked out at once; apply such a limit in the application
      if you need one.

## Quick Start

To use **keypool** in an existing SBT project with Scala 2.12 or a later version, add the following
dependencies to your `build.sbt` depending on your needs:

```scala
libraryDependencies += "org.typelevel" %% "keypool" % "@VERSION@"
```

**keypool** is cross-built with Scala versions 2.12, 2.13 and 3.3 for **JVM**, **Scala.js** and
**Scala Native**.


[cats-effect]: https://typelevel.org/cats-effect/

[typelevel]: https://typelevel.org
