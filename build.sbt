import com.typesafe.tools.mima.core._

val Scala213 = "2.13.8"

ThisBuild / tlBaseVersion := "0.4"
ThisBuild / crossScalaVersions := Seq("2.12.16", Scala213, "3.1.3")
ThisBuild / tlVersionIntroduced := Map("3" -> "0.4.3")
ThisBuild / developers += tlGitHubDev("ChristopherDavenport", "Christopher Davenport")
ThisBuild / startYear := Some(2019)
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / tlSiteApiUrl := Some(url("https://www.javadoc.io/doc/org.typelevel/keypool_2.12"))

lazy val root = tlCrossRootProject.aggregate(core)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "keypool",
    mimaPreviousArtifacts ~= { _.filterNot(_.revision == "0.4.4") }
  )
  .jsSettings(
    tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "0.4.6").toMap
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "org.typelevel" %% "otel4s-java"    % otel4sV % Test,
      "org.typelevel" %% "otel4s-testkit" % otel4sV % Test
    )
  )
  .settings(
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[DirectMissingMethodProblem]("org.typelevel.keypool.KeyPool.destroy"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("org.typelevel.keypool.KeyPool.reap"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("org.typelevel.keypool.KeyPool.put"),
      ProblemFilters
        .exclude[DirectMissingMethodProblem]("org.typelevel.keypool.KeyPool#KeyPoolConcrete.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "org.typelevel.keypool.KeyPool#KeyPoolConcrete.kpCreate"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "org.typelevel.keypool.KeyPool#KeyPoolConcrete.kpDestroy"
      ),
      ProblemFilters
        .exclude[DirectMissingMethodProblem]("org.typelevel.keypool.KeyPoolBuilder.this"),
      ProblemFilters
        .exclude[DirectMissingMethodProblem]("org.typelevel.keypool.KeyPool#Builder.this")
    )
  )

lazy val docs = project
  .in(file("site"))
  .settings(commonSettings)
  .dependsOn(core.jvm)
  .enablePlugins(TypelevelSitePlugin)

val catsV = "2.8.0"
val catsEffectV = "3.3.14"

val otel4sV = "0.0-2daac91-SNAPSHOT"

val munitV = "0.7.29"
val munitCatsEffectV = "1.0.7"

val kindProjectorV = "0.13.2"
val betterMonadicForV = "0.3.1"

// General Settings
lazy val commonSettings = Seq(
  Test / parallelExecution := false,
  resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
  libraryDependencies ++= Seq(
    "org.typelevel" %%% "cats-core"           % catsV,
    "org.typelevel" %%% "cats-effect-std"     % catsEffectV,
    "org.typelevel" %%% "otel4s-core"         % otel4sV,
    "org.typelevel" %%% "cats-effect-testkit" % catsEffectV      % Test,
    "org.scalameta" %%% "munit"               % munitV           % Test,
    "org.typelevel" %%% "munit-cats-effect-3" % munitCatsEffectV % Test
  )
)
