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

import java.lang.{ Class => jClass }
import java.lang.reflect.{ Member => jMember }

trait PrivateWithin {
  self: SymbolTable =>

  def propagatePackageBoundary(c: jClass[_], syms: Symbol*): Unit =
    propagatePackageBoundary(JavaAccFlags(c), syms: _*)
  def propagatePackageBoundary(m: jMember, syms: Symbol*): Unit =
    propagatePackageBoundary(JavaAccFlags(m), syms: _*)
  def propagatePackageBoundary(jflags: JavaAccFlags, syms: Symbol*): Unit = {
    if (jflags.hasPackageAccessBoundary)
      syms foreach setPackageAccessBoundary
  }

  // protected in java means package protected. #3946
  // See ticket #1687 for an example of when the enclosing top level class is NoSymbol;
  // it apparently occurs when processing v45.3 bytecode.
  def setPackageAccessBoundary(sym: Symbol): Symbol = {
    val topLevel = sym.enclosingTopLevelClass
    if (topLevel eq NoSymbol) sym
    else sym setPrivateWithin topLevel.owner
  }
}
