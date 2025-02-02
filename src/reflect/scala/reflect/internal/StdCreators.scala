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

package scala
package reflect
package internal

import scala.reflect.api.{TreeCreator, TypeCreator}
import scala.reflect.api.{Universe => ApiUniverse}

trait StdCreators {
  self: SymbolTable =>

  case class FixedMirrorTreeCreator(mirror: scala.reflect.api.Mirror[StdCreators.this.type], tree: Tree) extends TreeCreator {
    def apply[U <: ApiUniverse with Singleton](m: scala.reflect.api.Mirror[U]): U # Tree =
      if (m eq mirror) tree.asInstanceOf[U # Tree]
      else throw new IllegalArgumentException(s"Expr defined in $mirror cannot be migrated to other mirrors.")
  }

  case class FixedMirrorTypeCreator(mirror: scala.reflect.api.Mirror[StdCreators.this.type], tpe: Type) extends TypeCreator {
    def apply[U <: ApiUniverse with Singleton](m: scala.reflect.api.Mirror[U]): U # Type =
      if (m eq mirror) tpe.asInstanceOf[U # Type]
      else throw new IllegalArgumentException(s"Type tag defined in $mirror cannot be migrated to other mirrors.")
  }
}
