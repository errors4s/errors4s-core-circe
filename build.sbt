import _root_.io.isomarcte.sbt.version.scheme.enforcer.core._
import _root_.org.errors4s.sbt.GAVs._
import _root_.org.errors4s.sbt._

// Constants //

lazy val errors4sOrg   = "org.errors4s"
lazy val jreVersion    = "17"
lazy val projectName   = "errors4s-core-circe"
lazy val projectUrl    = url(s"https://github.com/errors4s/${projectName}")
lazy val scala212      = "2.12.15"
lazy val scala213      = "2.13.7"
lazy val scalaVersions = Set(scala212, scala213)

// SBT Command Aliases //

// Usually run before making a PR
addCommandAlias(
  "full_build",
  s";+clean;githubWorkflowGenerate;docs/mdoc;+test;+test:doc;+versionSchemeEnforcerCheck;++${scala213};scalafmtAll;scalafmtSbt;scalafixAll"
)

// Functions //

def isScala3(version: String): Boolean = version.startsWith("3")

def initialImports(packages: List[String], isScala3: Boolean): String = {
  val wildcard: Char =
    if (isScala3) {
      '*'
    } else {
      '_'
    }

  packages.map(value => s"import ${value}.${wildcard}").mkString("\n")
}

// Dependency Overrides //

ThisBuild / dependencyOverrides += G.scalametaG % A.semanticdbA % V.semanticdbV cross CrossVersion.full

// Common Settings //

ThisBuild / crossScalaVersions := scalaVersions.toSeq

ThisBuild / organization := errors4sOrg
ThisBuild / scalaVersion := scala213
ThisBuild / scalafixDependencies ++= List(G.organizeImportsG %% A.organizeImportsA % V.organizeImportsV)
ThisBuild / scalafixScalaBinaryVersion := "2.13"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// Baseline version for repo split

ThisBuild / versionSchemeEnforcerInitialVersion := Some("1.0.0.0")
ThisBuild / versionScheme := Some("pvp")

// GithubWorkflow
ThisBuild / githubWorkflowPublishTargetBranches := Nil
ThisBuild / githubWorkflowOSes := Set("macos-latest", "ubuntu-latest").toList
ThisBuild / githubWorkflowJavaVersions :=
  Set("17", "11", "8").map(version => JavaSpec(JavaSpec.Distribution.Temurin, version)).toList
ThisBuild / githubWorkflowBuild := List(WorkflowStep.Sbt(List("versionSchemeEnforcerCheck", "Test / doc")))

// Doc Settings

def scaladocLink(scalaBinaryVersion: String, version: String): String =
  s"https://www.javadoc.io/doc/${errors4sOrg}/${projectName}_${scalaBinaryVersion}/${version}/index.html"

lazy val docSettings: List[Def.Setting[_]] = List(
  apiURL := Some(url(scaladocLink(scalaBinaryVersion.value, version.value))),
  autoAPIMappings := true,
  Compile / doc / apiMappings := {
    if (isScala3(scalaBinaryVersion.value)) {
      Map.empty[File, URL]
    } else {
      val moduleLink: String => (java.io.File, java.net.URL) = module => ScalaApiDoc.jreModuleLink(jreVersion)(module)
      Map(moduleLink("java.base"))
    }
  },
  Compile / doc / scalacOptions := {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, _)) =>
        List(
          s"-external-mappings:.*java.*::javadoc::https://docs.oracle.com/en/java/javase/${jreVersion}/docs/api/java.base/",
          s"-social-links:github::https://github.com/errors4s/${projectName}"
        )
      case Some((2, n)) =>
        List("-language:experimental.macros") ++
          (if (n <= 12) {
             List("-no-link-warnings")
           } else {
             List("-jdk-api-doc-base", s"https://docs.oracle.com/en/java/javase/${jreVersion}/docs/api")
           })
      case otherwise =>
        throw new AssertionError(s"Unhandled Scala version in Compile / doc/ scalacOptions: ${otherwise}")
    }
  }
)

lazy val commonSettings: List[Def.Setting[_]] =
  List(
    scalaVersion := scala213,
    scalacOptions := {
      val currentOptions: Seq[String] = scalacOptions.value
      (
        if (isScala3(scalaBinaryVersion.value)) {
          // Remove -source since as of 0.1.19 of sbt-tpolecat it sets -source
          // to be `-source:future`, but we only want that on sources which are
          // _strictly_ Scala 3, we want `-source:3.0-migration` from cross
          // compiled sources.
          currentOptions.filterNot(_.startsWith("-source")) ++ List("-source:3.0-migration") ++
            (if (JREMajorVersion.majorVersion > 8) {
               List("-release:8")
             } else {
               Nil
             })
        } else {
          currentOptions ++ List("-target:jvm-1.8", "-Wconf:cat=unused-imports:info")
        }
      )
    },
    libraryDependencies ++= {
      if (isScala3(scalaBinaryVersion.value)) {
        Nil
      } else {
        List(
          compilerPlugin(G.betterMonadicForG %% A.betterMonadicForA % V.betterMonadicForV),
          compilerPlugin(G.typelevelG         % A.kindProjectorA    % V.kindProjectorV cross CrossVersion.full)
        )
      }
    },
    crossScalaVersions := scalaVersions.toSeq
  ) ++ docSettings

// Publish Settings //

lazy val publishSettings = List(
  homepage := Some(projectUrl),
  licenses := Seq("BSD3" -> url("https://opensource.org/licenses/BSD-3-Clause")),
  publishMavenStyle := true,
  Test / publishArtifact := false,
  pomIncludeRepository := { _ =>
    false
  },
  publishTo := {
    val nexus = "https://s01.oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  scmInfo := Some(ScmInfo(projectUrl, s"scm:git:git@github.com:errors4s/${projectName}.git")),
  developers :=
    List(Developer("isomarcte", "David Strawn", "isomarcte@gmail.com", url("https://github.com/isomarcte"))),
  credentials += Credentials(Path.userHome / ".sbt" / ".credentials")
)

// Root //

lazy val root = (project in file("."))
  .settings(commonSettings, publishSettings)
  .settings(
    List(
      name := s"${projectName}-root",
      Compile / packageBin / publishArtifact := false,
      Compile / packageSrc / publishArtifact := false
    )
  )
  .aggregate(circe)
  .disablePlugins(SbtVersionSchemeEnforcerPlugin)

// Circe //

lazy val circe = project
  .settings(commonSettings, publishSettings)
  .settings(
    name := s"${projectName}",
    console / initialCommands :=
      List("org.errors4s.core._", "org.errors4s.core.syntax.all._", "org.errors4s.core.circe.instances._")
        .map(value => s"import $value")
        .mkString("\n"),
    libraryDependencies ++= List(G.circeG %% A.circeCoreA % V.circeV, errors4sOrg %% A.errors4sCoreA % V.errors4sCoreV),
    libraryDependencies ++=
      List(
        G.circeG     %% A.circeTestingA           % V.circeV,
        G.typelevelG %% A.disciplineMunitA        % V.disciplineMunitV,
        errors4sOrg  %% A.errors4sCoreCatsA       % V.errors4sCoreCatsV,
        errors4sOrg  %% A.errors4sCoreScalacheckA % V.errors4sCoreScalacheckV
      ).map(_ % Test)
  )

// Docs //

lazy val docs = (project.in(file(s"${projectName}-docs")))
  .settings(commonSettings)
  .settings(
    name := s"${projectName}-docs",
    mdocVariables := {
      val latestRelease: String =
        // Need to wait for sbt-version-scheme-enforcer 2.1.2.0 for this to
        // work dynamically.
        "1.0.0.0"
      val scalaBinVer: String = scalaBinaryVersion.value

      Map(
        "LATEST_RELEASE"       -> latestRelease,
        "SCALA_BINARY_VERSION" -> scalaBinVer,
        "SCALADOC_LINK"        -> scaladocLink(scalaBinVer, latestRelease),
        "ORG"                  -> errors4sOrg,
        "PROJECT_NAME"         -> projectName
      )
    },
    mdocIn := file("docs-src"),
    mdocOut := file("docs")
  )
  .dependsOn(circe)
  .enablePlugins(MdocPlugin)
