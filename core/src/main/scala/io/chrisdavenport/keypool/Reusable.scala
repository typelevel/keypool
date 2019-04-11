package io.chrisdavenport.keypool

sealed trait Reusable
case object Reuse extends Reusable
case object DontReuse extends Reusable