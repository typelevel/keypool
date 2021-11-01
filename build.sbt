import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

val Scala213 = "2.13.7"

ThisBuild / crossScalaVersions := Seq("2.12.15", Scala213, "3.0.2")
ThisBuild / scalaVersion := crossScalaVersions.value.last

ThisBuild / githubWorkflowArtifactUpload := false

val Scala213Cond = s"matrix.scala == '$Scala213'"
val JVMCond = "matrix.platform == 'JVM'"
val JSCond = "matrix.platform == 'JS'"

ThisBuild / githubWorkflowBuildMatrixAdditions += "platform" -> List("JVM", "JS")

def rubySetupSteps(cond: Option[String]) = Seq(
  WorkflowStep.Use(
    UseRef.Public("ruby", "setup-ruby", "v1"),
    name = Some("Setup Ruby"),
    params = Map("ruby-version" -> "2.6.0"),
    cond = cond
  ),
  WorkflowStep.Run(
    List("gem install saas", "gem install jekyll -v 3.2.1"),
    name = Some("Install microsite dependencies"),
    cond = cond
  )
)

ThisBuild / githubWorkflowBuildPreamble ++=
  rubySetupSteps(Some(Scala213Cond + " && " + JVMCond))

ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep
    .Sbt(List("scalafmtCheckAll", "scalafmtSbtCheck"), name = Some("Check formatting")),
  WorkflowStep.Sbt(List("mimaReportBinaryIssues"), name = Some("Check binary issues")),
  WorkflowStep.Sbt(List("core${{ matrix.platform }}/test"), name = Some("Compile and run tests")),
  WorkflowStep.Sbt(
    List("docs/makeMicrosite"),
    cond = Some(Scala213Cond + " && " + JVMCond),
    name = Some("Build the Microsite")
  )
)

ThisBuild / githubWorkflowTargetBranches := List("*", "series/*")
ThisBuild / githubWorkflowTargetTags ++= Seq("v*")

// currently only publishing tags
ThisBuild / githubWorkflowPublishTargetBranches :=
  Seq(RefPredicate.StartsWith(Ref.Tag("v")))

ThisBuild / githubWorkflowBuildPreamble +=
  WorkflowStep.Use(
    UseRef.Public("actions", "setup-node", "v2.1.5"),
    name = Some("Setup NodeJS v14 LTS"),
    params = Map("node-version" -> "14"),
    cond = Some(JSCond)
  )
ThisBuild / githubWorkflowPublishPreamble ++=
  WorkflowStep.Use(UseRef.Public("olafurpg", "setup-gpg", "v3")) +: rubySetupSteps(None)

ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Sbt(
    List("ci-release"),
    name = Some("Publish artifacts to Sonatype"),
    env = Map(
      "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
    )
  ),
  WorkflowStep.Sbt(
    List(s"++$Scala213", "docs/publishMicrosite"),
    name = Some("Publish microsite")
  )
)

lazy val `keypool` = project
  .in(file("."))
  .disablePlugins(MimaPlugin)
  .settings(commonSettings, releaseSettings, skipOnPublishSettings)
  .aggregate(core.jvm, core.js)

lazy val core = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(commonSettings, releaseSettings, mimaSettings)
  .jsSettings(Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
  .settings(
    name := "keypool"
  )

lazy val docs = project
  .in(file("site"))
  .settings(commonSettings, skipOnPublishSettings, micrositeSettings)
  .dependsOn(core.jvm)
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(MdocPlugin)

lazy val contributors = Seq(
  "ChristopherDavenport" -> "Christopher Davenport"
)

val catsV = "2.6.1"
val catsEffectV = "3.2.9"

val munitCatsEffectV = "1.0.6"

val kindProjectorV = "0.13.2"
val betterMonadicForV = "0.3.1"

// General Settings
lazy val commonSettings = Seq(
  organization := "org.typelevel",
  testFrameworks += new TestFramework("munit.Framework"),
  Compile / doc / scalacOptions ++= Seq(
    "-groups",
    "-sourcepath",
    (LocalRootProject / baseDirectory).value.getAbsolutePath,
    "-doc-source-url",
    "https://github.com/typelevel/keypool/blob/v" + version.value + "€{FILE_PATH}.scala"
  ),
  Test / parallelExecution := false,
  libraryDependencies ++= {
    if (ScalaArtifacts.isScala3(scalaVersion.value)) Seq.empty
    else
      Seq(
        compilerPlugin("org.typelevel" % "kind-projector" % kindProjectorV cross CrossVersion.full),
        compilerPlugin("com.olegpy" %% "better-monadic-for" % betterMonadicForV)
      )
  },
  scalacOptions ++= {
    if (ScalaArtifacts.isScala3(scalaVersion.value)) Seq("-source:3.0-migration")
    else Seq()
  },
  Compile / doc / sources := {
    val old = (Compile / doc / sources).value
    if (ScalaArtifacts.isScala3(scalaVersion.value))
      Seq()
    else
      old
  },
  libraryDependencies ++= Seq(
    "org.typelevel" %%% "cats-core"           % catsV,
    "org.typelevel" %%% "cats-effect-kernel"  % catsEffectV,
    "org.typelevel" %%% "cats-effect-std"     % catsEffectV      % Test,
    "org.typelevel" %%% "munit-cats-effect-3" % munitCatsEffectV % Test
  )
)

lazy val releaseSettings = {
  Seq(
    Test / publishArtifact := false,
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/typelevel/keypool"),
        "git@github.com:typelevel/keypool.git"
      )
    ),
    homepage := Some(url("https://github.com/typelevel/keypool")),
    licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
    publishMavenStyle := true,
    pomIncludeRepository := { _ =>
      false
    },
    pomExtra := {
      <developers>
        {
        for ((username, name) <- contributors)
          yield <developer>
          <id>{username}</id>
          <name>{name}</name>
          <url>http://github.com/{username}</url>
        </developer>
      }
      </developers>
    }
  )
}

lazy val mimaSettings = {

  def semverBinCompatVersions(major: Int, minor: Int, patch: Int): Set[(Int, Int, Int)] = {
    val majorVersions: List[Int] =
      if (major == 0 && minor == 0) List.empty[Int] // If 0.0.x do not check MiMa
      else List(major)
    val minorVersions: List[Int] =
      if (major >= 1) Range(0, minor).inclusive.toList
      else List(minor)
    def patchVersions(currentMinVersion: Int): List[Int] =
      if (minor == 0 && patch == 0) List.empty[Int]
      else if (currentMinVersion != minor) List(0)
      else Range(0, patch - 1).inclusive.toList

    val versions = for {
      maj <- majorVersions
      min <- minorVersions
      pat <- patchVersions(min)
    } yield (maj, min, pat)
    versions.toSet
  }

  def mimaVersions(version: String): Set[String] = {
    VersionNumber(version) match {
      case VersionNumber(Seq(major, minor, patch, _*), _, _) if patch.toInt > 0 =>
        semverBinCompatVersions(major.toInt, minor.toInt, patch.toInt)
          .map { case (maj, min, pat) => maj.toString + "." + min.toString + "." + pat.toString }
      case _ =>
        Set.empty[String]
    }
  }
  // Safety Net For Exclusions
  lazy val excludedVersions: Set[String] = Set(
    "0.3.1", // failed to publish
    "0.3.2", // failed to publish
    "0.4.3", // published, but failed to synchronize
    "0.4.4" // failed to publish
  )

  // Safety Net for Inclusions
  lazy val extraVersions: Set[String] = Set()

  Seq(
    mimaFailOnNoPrevious := false,
    mimaFailOnProblem := mimaVersions(version.value).toList.nonEmpty,
    mimaPreviousArtifacts := (mimaVersions(version.value) ++ extraVersions)
      .diff(excludedVersions)
      .filterNot(Function.const(scalaVersion.value == "2.13.0-M5"))
      .filterNot(Function.const(ScalaArtifacts.isScala3(scalaVersion.value)))
      .map { v =>
        val moduleN = moduleName.value + "_" + scalaBinaryVersion.value.toString
        organization.value % moduleN % v
      },
    mimaBinaryIssueFilters ++= {
      import com.typesafe.tools.mima.core._
      import com.typesafe.tools.mima.core.ProblemFilters._
      Seq()
    }
  )
}

lazy val micrositeSettings = {
  import microsites._
  Seq(
    mimaFailOnNoPrevious := false,
    mimaPreviousArtifacts := Set(),
    micrositeName := "keypool",
    micrositeDescription := "A Keyed Pool for Scala",
    micrositeAuthor := "Christopher Davenport",
    micrositeGithubOwner := "ChristopherDavenport",
    micrositeGithubRepo := "keypool",
    micrositeBaseUrl := "/keypool",
    micrositeDocumentationUrl := "https://www.javadoc.io/doc/org.typelevel/keypool_2.12",
    micrositeFooterText := None,
    micrositeHighlightTheme := "atom-one-light",
    micrositePalette := Map(
      "brand-primary" -> "#3e5b95",
      "brand-secondary" -> "#294066",
      "brand-tertiary" -> "#2d5799",
      "gray-dark" -> "#49494B",
      "gray" -> "#7B7B7E",
      "gray-light" -> "#E5E5E6",
      "gray-lighter" -> "#F4F3F4",
      "white-color" -> "#FFFFFF"
    ),
    micrositePushSiteWith := GitHub4s,
    micrositeGithubToken := sys.env.get("GITHUB_TOKEN"),
    micrositeExtraMdFiles := Map(
      file("CHANGELOG.md") -> ExtraMdFileConfig(
        "changelog.md",
        "page",
        Map("title" -> "changelog", "section" -> "changelog", "position" -> "100")
      ),
      file("CODE_OF_CONDUCT.md") -> ExtraMdFileConfig(
        "code-of-conduct.md",
        "page",
        Map("title" -> "code of conduct", "section" -> "code of conduct", "position" -> "101")
      ),
      file("LICENSE") -> ExtraMdFileConfig(
        "license.md",
        "page",
        Map("title" -> "license", "section" -> "license", "position" -> "102")
      )
    )
  )
}

lazy val skipOnPublishSettings = Seq(
  publish / skip := true,
  publish := (()),
  publishLocal := (()),
  publishArtifact := false,
  publishTo := None
)
