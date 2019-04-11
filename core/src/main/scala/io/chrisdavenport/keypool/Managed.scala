package io.chrisdavenport.keypool

import cats.effect.concurrent.Ref

final class Managed[F[_], Rezource] private[keypool] (
  val resource: Rezource,
  val isReused: Boolean,
  val canBeReused: Ref[F, Reusable]
)