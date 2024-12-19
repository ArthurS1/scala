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

package scala.reflect.macros
package runtime

import scala.reflect.internal.util.Position
import scala.util.control.ControlThrowable

class AbortMacroException(val pos: Position, val msg: String) extends Throwable(msg) with ControlThrowable