# keypool - A Keyed Pool for Scala

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.typelevel/keypool_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.typelevel/keypool_2.13)

## Overview

**keypool** provides a lightweight keyed resource pool abstraction for controlling concurrent access
to reusable resources. It limits concurrent acquisitions and reclaims resources to prevent leaks.
Per-key isolation and deterministic lifecycle semantics enable predictable behavior under load.

The library is designed for integration within the [Typelevel][typelevel] ecosystem. It is
implemented on top of [Cats Effect][cats-effect] type classes using **Polymorphic Effects** (a.k.a.
**Tagless Final**).

The library provides the following primary implementations:

- [`Pool`] – a simple specialization that manages reusable resources without partitioning by
  key. Use it when you need a single shared pool of identical resources, such as a pool of
  connections to a single endpoint when connection partitioning isn't required.
- [`KeyPool`] – a general-purpose pool that partitions resources by a user-defined key. Use
  it when you need per-key isolation and limits, for example:
    - In cluster-based deployments, to prevent one node from starving others by partitioning
      connections into separate per-node pools.
    - In multi-tenant systems, to limit how many database connections or active API sessions each
      tenant can use.

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
