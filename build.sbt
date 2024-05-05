import com.typesafe.tools.mima.core._

val Scala213 = "2.13.13"
val Scala3 = "3.3.3"

ThisBuild / tlBaseVersion := "0.4"
ThisBuild / crossScalaVersions := Seq("2.12.19", Scala213, Scala3)
ThisBuild / tlVersionIntroduced := Map("3" -> "0.4.3")
ThisBuild / developers += tlGitHubDev("ChristopherDavenport", "Christopher Davenport")
ThisBuild / startYear := Some(2019)
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / tlSiteApiUrl := Some(url("https://www.javadoc.io/doc/org.typelevel/keypool_2.12"))

lazy val root = tlCrossRootProject.aggregate(core, otel4s)

ThisBuild / githubWorkflowBuildMatrixAdditions := {
  val projects = core.componentProjects ++ otel4s.componentProjects

  Map("project" -> projects.map(_.id).toList)
}

ThisBuild / githubWorkflowBuildMatrixExclusions ++= {
  val projects = otel4s.componentProjects.map(_.id)
  projects.map(project => MatrixExclude(Map("project" -> project, "scala" -> "2.12")))
}

lazy val core = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "keypool",
    mimaPreviousArtifacts ~= { _.filterNot(_.revision == "0.4.4") }
  )
  .jsSettings(
    tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "0.4.6").toMap
  )
  .nativeSettings(
    tlVersionIntroduced := List("2.12", "2.13", "3").map(_ -> "0.4.8").toMap
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
        .exclude[DirectMissingMethodProblem]("org.typelevel.keypool.KeyPool#Builder.this"),
      ProblemFilters.exclude[DirectMissingMethodProblem]("org.typelevel.keypool.Pool#Builder.this")
    )
  )

lazy val otel4s = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("otel4s"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "keypool-otel4s",
    startYear := Some(2024),
    crossScalaVersions := Seq(Scala213, Scala3),
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "otel4s-core-metrics"        % otel4sV,
      "org.typelevel" %%% "otel4s-sdk-metrics-testkit" % otel4sV % Test
    ),
    mimaPreviousArtifacts ~= { _.filterNot(_.revision.startsWith("0.4")) }
  )

lazy val docs = project
  .in(file("site"))
  .settings(commonSettings)
  .dependsOn(core.jvm, otel4s.jvm)
  .enablePlugins(TypelevelSitePlugin)

val catsV = "2.10.0"
val catsEffectV = "3.5.4"

val otel4sV = "0.7.0"

val munitV = "1.0.0-RC1"
val munitCatsEffectV = "2.0.0-M5"

val kindProjectorV = "0.13.3"
val betterMonadicForV = "0.3.1"

// General Settings
lazy val commonSettings = Seq(
  Test / parallelExecution := false,
  libraryDependencies ++= Seq(
    "org.typelevel" %%% "cats-core"           % catsV,
    "org.typelevel" %%% "cats-effect-std"     % catsEffectV,
    "org.typelevel" %%% "cats-effect-testkit" % catsEffectV      % Test,
    "org.scalameta" %%% "munit"               % munitV           % Test,
    "org.typelevel" %%% "munit-cats-effect"   % munitCatsEffectV % Test
  )
)
