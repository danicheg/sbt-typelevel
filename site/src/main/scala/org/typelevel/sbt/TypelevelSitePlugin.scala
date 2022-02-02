/*
 * Copyright 2022 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.typelevel.sbt

import sbt._, Keys._
import mdoc.MdocPlugin, MdocPlugin.autoImport._
import laika.ast._
import laika.ast.LengthUnit._
import laika.sbt.LaikaPlugin, LaikaPlugin.autoImport._
import laika.helium.Helium
import laika.helium.config.{Favicon, HeliumIcon, IconLink, ImageLink}
import org.typelevel.sbt.kernel.GitHelper
import gha.GenerativePlugin, GenerativePlugin.autoImport._
import laika.ast.Path.Root
import scala.io.Source
import java.util.Base64
import scala.annotation.nowarn

object TypelevelSitePlugin extends AutoPlugin {

  object autoImport {
    lazy val tlSiteHeliumConfig = settingKey[Helium]("The Helium configuration")
    lazy val tlSiteApiUrl = settingKey[Option[URL]]("URL to the API docs")
    lazy val tlSiteGenerate = settingKey[Seq[WorkflowStep]](
      "A sequence of workflow steps which generates the site (default: [Sbt(List(\"tlSite\"))])")
    lazy val tlSitePublish = settingKey[Seq[WorkflowStep]](
      "A sequence of workflow steps which publishes the site (default: peaceiris/actions-gh-pages)")
    lazy val tlSitePublishBranch = settingKey[Option[String]](
      "The branch to publish the site from on every push. Set this to None if you only want to update the site on tag releases. (default: main)")
    lazy val tlSite = taskKey[Unit]("Generate the site (default: runs mdoc then laika)")
  }

  import autoImport._

  override def requires = MdocPlugin && LaikaPlugin && GenerativePlugin && NoPublishPlugin

  override def buildSettings = Seq(
    tlSitePublishBranch := Some("main"),
    tlSiteApiUrl := None
  )

  val faviconPath: Path = Root / "images" / "favicon.png"

  override def projectSettings = Seq(
    laikaInputs ~= {
      _.delegate.addStream(
        cats.effect.IO.blocking(getClass.getResourceAsStream("/images/favicon.png")),
        faviconPath
      )
    },
    tlSite := Def
      .sequential(
        mdoc.toTask(""),
        laikaSite
      )
      .value: @nowarn("cat=other-pure-statement"),
    Laika / sourceDirectories := Seq(mdocOut.value),
    laikaTheme := tlSiteHeliumConfig.value.build,
    mdocVariables ++= Map(
      "VERSION" -> GitHelper
        .previousReleases()
        .filterNot(_.isPrerelease)
        .headOption
        .fold(version.value)(_.toString),
      "SNAPSHOT_VERSION" -> version.value
    ),
    tlSiteHeliumConfig := {
      Helium
        .defaults
        .site
        .layout(
          contentWidth = px(860),
          navigationWidth = px(275),
          topBarHeight = px(50),
          defaultBlockSpacing = px(10),
          defaultLineHeight = 1.5,
          anchorPlacement = laika.helium.config.AnchorPlacement.Right
        )
        .site
        .favIcons(
          Favicon.internal(faviconPath, "32x32")
        )
        .site
        .topNavigationBar(
          homeLink = ImageLink.external(
            "https://typelevel.org",
            Image.external(s"data:image/svg+xml;base64,$getSvgLogo")
          ),
          navLinks = tlSiteApiUrl.value.toList.map { url =>
            IconLink.external(
              url.toString,
              HeliumIcon.api,
              options = Styles("svg-link")
            )
          } ++ List(
            IconLink.external(
              scmInfo.value.fold("https://github.com/typelevel")(_.browseUrl.toString),
              HeliumIcon.github,
              options = Styles("svg-link")),
            IconLink.external("https://discord.gg/XF3CXcMzqD", HeliumIcon.chat),
            IconLink.external("https://twitter.com/typelevel", HeliumIcon.twitter)
          )
        )
    },
    laikaExtensions ++= Seq(
      laika.markdown.github.GitHubFlavor,
      laika.parse.code.SyntaxHighlighting
    ),
    tlSiteGenerate := List(
      WorkflowStep.Sbt(
        List(s"${thisProject.value.id}/${tlSite.key.toString}"),
        name = Some("Generate site")
      )
    ),
    tlSitePublish := List(
      WorkflowStep.Use(
        UseRef.Public("peaceiris", "actions-gh-pages", "v3.8.0"),
        Map(
          "github_token" -> s"$${{ secrets.GITHUB_TOKEN }}",
          "publish_dir" -> (ThisBuild / baseDirectory)
            .value
            .toPath
            .toAbsolutePath
            .relativize(((Laika / target).value / "site").toPath)
            .toString,
          "publish_branch" -> "gh-pages"
        ),
        name = Some("Publish site"),
        cond = {
          val predicate = tlSitePublishBranch
            .value // Either publish from branch or on tags, not both
            .fold[RefPredicate](RefPredicate.StartsWith(Ref.Tag("v")))(b =>
              RefPredicate.Equals(Ref.Branch(b)))
          val publicationCond = GenerativePlugin.compileBranchPredicate("github.ref", predicate)
          Some(s"github.event_name != 'pull_request' && $publicationCond")
        }
      )
    ),
    ThisBuild / githubWorkflowAddedJobs +=
      WorkflowJob(
        "site",
        "Generate Site",
        scalas = List((ThisBuild / scalaVersion).value),
        javas = List(githubWorkflowJavaVersions.value.head),
        steps =
          githubWorkflowJobSetup.value.toList ++ tlSiteGenerate.value ++ tlSitePublish.value
      )
  )

  private def getSvgLogo: String = {
    val src = Source.fromURL(getClass.getResource("/images/logo.svg"))
    try {
      Base64.getEncoder().encodeToString(src.mkString.getBytes)
    } finally {
      src.close()
    }
  }

}
