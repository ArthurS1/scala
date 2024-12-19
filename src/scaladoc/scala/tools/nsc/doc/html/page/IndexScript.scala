/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc.doc
package html
package page

import scala.tools.nsc.doc
import scala.tools.nsc.doc.model.{Package, DocTemplateEntity}
import scala.tools.nsc.doc.html.{Page, HtmlFactory}

class IndexScript(universe: doc.Universe) extends Page {
  import model._
  import scala.tools.nsc.doc.base.comment.Text
  import scala.collection.immutable.Map

  def path = List("index.js")

  override def writeFor(site: HtmlFactory) {
    writeFile(site) {
      _.write(s"Index.PACKAGES = $packages;")
    }
  }

  val packages = {
    val pairs = allPackagesWithTemplates.toIterable.map(_ match {
      case (pack, templates) => {
        val merged = mergeByQualifiedName(templates)

        val ary = merged.keys.toVector.sortBy(_.toLowerCase).map { key =>
          /** One pair is generated for the class/trait and one for the
           *  companion object, both will have the same {"name": key}
           *
           *  As such, we need to distinguish between the members that are
           *  generated by the object, and the members generated by the
           *  class/trait instance. Otherwise one of the member objects will be
           *  overwritten.
           */
          val pairs = merged(key).flatMap { t: DocTemplateEntity =>
            val kind = kindToString(t)
            Seq(
              kind -> relativeLinkTo(t),
              "kind" -> kind,
              s"members_$kind" -> membersToJSON(t.members.toVector.filter(!_.isShadowedOrAmbiguousImplicit), t),
              "shortDescription" -> shortDesc(t))
          }

          JSONObject(Map(pairs : _*) + ("name" -> key))
        }

        pack.qualifiedName -> JSONArray(ary)
      }
    }).toSeq

    JSONObject(Map(pairs : _*))
  }

  private def mergeByQualifiedName(source: List[DocTemplateEntity]): collection.mutable.Map[String, List[DocTemplateEntity]] = {
    val result = collection.mutable.Map[String, List[DocTemplateEntity]]()

    for (t <- source) {
      val k = t.qualifiedName
      result += k -> (result.getOrElse(k, Nil) :+ t)
    }

    result
  }

  def allPackages: List[Package] = {
    def f(parent: Package): List[Package] = {
      parent.packages.flatMap(
        p => f(p) :+ p
      )
    }
    f(universe.rootPackage).sortBy(_.toString)
  }

  def allPackagesWithTemplates: Map[Package, List[DocTemplateEntity]] = {
    Map(allPackages.map((key) => {
      key -> key.templates.collect {
        case t: DocTemplateEntity if !t.isPackage && !universe.settings.hardcoded.isExcluded(t.qualifiedName) => t
      }
    }) : _*)
  }

  /** Gets the short description i.e. the first sentence of the docstring */
  def shortDesc(mbr: MemberEntity): String = mbr.comment.fold("") { c =>
    Page.inlineToStr(c.short).replaceAll("\n", "")
  }

  /** Returns the json representation of the supplied members */
  def membersToJSON(entities: Vector[MemberEntity], parent: DocTemplateEntity): JSONArray =
    JSONArray(entities.map(memberToJSON(_, parent)))

  private def memberToJSON(mbr: MemberEntity, parent: DocTemplateEntity): JSONObject = {
    /** This function takes a member and gets eventual parameters and the
     *  return type. For example, the definition:
     *  {{{ def get(key: A): Option[B] }}}
     *  Gets turned into: "(key: A): Option[B]"
     */
    def memberTail: MemberEntity => String = {
      case d: Def => d
          .valueParams //List[List[ValueParam]]
          .map { params =>
            params.map(p => p.name + ": " + p.resultType.name).mkString(", ")
          }
          .mkString("(", ")(", "): " + d.resultType.name)
      case v: Val => ": " + v.resultType.name
      case _ => ""
    }

    /** This function takes a member entity and return all modifiers in a
     *  string, example:
     *  {{{ lazy val scalaProps: java.util.Properties }}}
     *  Gets turned into: "lazy val"
     */
    def memberKindToString(mbr: MemberEntity): String = {
      val kind = mbr.flags.map(_.text.asInstanceOf[Text].text).mkString(" ")
      val space = if (kind == "") "" else " "

      kind + space + kindToString(mbr)
    }

    /** This function turns a member entity into a JSON object that the index.js
     *  script can use to render search results
     */
    def jsonObject(m: MemberEntity): JSONObject =
      JSONObject(Map(
        "label"  -> "[^\\.]*\\.([^#]+#)?".r.replaceAllIn(m.definitionName, ""), // member name
        "member" -> m.definitionName.replaceFirst("#", "."), // full member name
        "tail"   -> memberTail(m),
        "kind"   -> memberKindToString(m), // modifiers i.e. "abstract def"
        "link"   -> memberToUrl(m)))       // permalink to the member

    mbr match {
      case x @ (_: Def | _: Val | _: Object | _: AliasType) => jsonObject(x)
      case e @ (_: Class | _: Trait) if parent.isRootPackage || !parent.isPackage => jsonObject(e)
      case m: MemberEntity =>
        JSONObject(Map("member" -> m.definitionName, "error" -> "unsupported entity"))
    }
  }

  def memberToUrl(mbr: MemberEntity): String = {
    val path = templateToPath(mbr.inTemplate).reverse.mkString("/")
    s"$path#${mbr.signature}"
  }
}

object IndexScript {
  def apply(universe: doc.Universe) = new IndexScript(universe)
}
