val sbtTypelevelVersion = "0.8.0"

addSbtPlugin("org.scala-js"       % "sbt-scalajs"                   % "1.19.0")
addSbtPlugin("org.scala-native"   % "sbt-scala-native"              % "0.5.8")
addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")
addSbtPlugin("com.github.cb372"   % "sbt-explicit-dependencies"     % "0.3.1")
addSbtPlugin("org.typelevel"      % "sbt-typelevel"                 % sbtTypelevelVersion)
addSbtPlugin("org.typelevel"      % "sbt-typelevel-site"            % sbtTypelevelVersion)
