val Scala213 = "2.13.8"

ThisBuild / tlBaseVersion := "0.4"
ThisBuild / crossScalaVersions := Seq("2.12.15", Scala213, "3.0.2")
ThisBuild / tlVersionIntroduced := Map("3" -> "0.4.3")
ThisBuild / developers += tlGitHubDev("ChristopherDavenport", "Christopher Davenport")
ThisBuild / startYear := Some(2019)
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / tlSiteApiUrl := Some(url("https://www.javadoc.io/doc/org.typelevel/keypool_2.12"))

lazy val root = tlCrossRootProject.aggregate(core)

lazy val core = crossProject(JVMPlatform, JSPlatform)
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

lazy val docs = project
  .in(file("site"))
  .settings(commonSettings)
  .dependsOn(core.jvm)
  .enablePlugins(TypelevelSitePlugin)

val catsV = "2.7.0"
val catsEffectV = "3.3.4"

val munitV = "0.7.29"
val munitCatsEffectV = "1.0.7"

val kindProjectorV = "0.13.2"
val betterMonadicForV = "0.3.1"

// General Settings
lazy val commonSettings = Seq(
  Test / parallelExecution := false,
  libraryDependencies ++= Seq(
    "org.typelevel" %%% "cats-core"           % catsV,
    "org.typelevel" %%% "cats-effect-kernel"  % catsEffectV,
    "org.typelevel" %%% "cats-effect-std"     % catsEffectV      % Test,
    "org.scalameta" %%% "munit"               % munitV           % Test,
    "org.typelevel" %%% "munit-cats-effect-3" % munitCatsEffectV % Test
  )
)
