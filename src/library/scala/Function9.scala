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

// GENERATED CODE: DO NOT EDIT. See scala.Function0 for timestamp.

package scala


/** A function of 9 parameters.
 *
 */
trait Function9[-T1, -T2, -T3, -T4, -T5, -T6, -T7, -T8, -T9, +R] extends AnyRef { self =>
  /** Apply the body of this function to the arguments.
   *  @return   the result of function application.
   */
  def apply(v1: T1, v2: T2, v3: T3, v4: T4, v5: T5, v6: T6, v7: T7, v8: T8, v9: T9): R
  /** Creates a curried version of this function.
   *
   *  @return   a function `f` such that `f(x1)(x2)(x3)(x4)(x5)(x6)(x7)(x8)(x9) == apply(x1, x2, x3, x4, x5, x6, x7, x8, x9)`
   */
  @annotation.unspecialized def curried: T1 => T2 => T3 => T4 => T5 => T6 => T7 => T8 => T9 => R = {
    (x1: T1) => ((x2: T2, x3: T3, x4: T4, x5: T5, x6: T6, x7: T7, x8: T8, x9: T9) => self.apply(x1, x2, x3, x4, x5, x6, x7, x8, x9)).curried
  }
  /** Creates a tupled version of this function: instead of 9 arguments,
   *  it accepts a single [[scala.Tuple9]] argument.
   *
   *  @return   a function `f` such that `f((x1, x2, x3, x4, x5, x6, x7, x8, x9)) == f(Tuple9(x1, x2, x3, x4, x5, x6, x7, x8, x9)) == apply(x1, x2, x3, x4, x5, x6, x7, x8, x9)`
   */

  @annotation.unspecialized def tupled: Tuple9[T1, T2, T3, T4, T5, T6, T7, T8, T9] => R = {
    case Tuple9(x1, x2, x3, x4, x5, x6, x7, x8, x9) => apply(x1, x2, x3, x4, x5, x6, x7, x8, x9)
  }
  override def toString() = "<function9>"
}
