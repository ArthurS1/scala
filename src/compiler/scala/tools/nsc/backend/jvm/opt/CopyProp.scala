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

package scala.tools.nsc
package backend.jvm
package opt

import scala.annotation.{switch, tailrec}
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.asm.Opcodes._
import scala.tools.asm.Type
import scala.tools.asm.tree._
import scala.tools.nsc.backend.jvm.BTypes.InternalName
import scala.tools.nsc.backend.jvm.analysis.BackendUtils.{LambdaMetaFactoryCall, _}
import scala.tools.nsc.backend.jvm.analysis._
import scala.tools.nsc.backend.jvm.opt.BytecodeUtils._

abstract class CopyProp {
  val postProcessor: PostProcessor

  import postProcessor.{backendUtils, callGraph, bTypes}
  import postProcessor.bTypes.frontendAccess.compilerSettings
  import backendUtils._


  /**
   * For every `xLOAD n`, find all local variable slots that are aliases of `n` using an
   * AliasingAnalyzer and change the instruction to `xLOAD m` where `m` is the smallest alias.
   * This leaves behind potentially stale `xSTORE n` instructions, which are then eliminated
   * by [[eliminateStaleStoresAndRewriteSomeIntrinsics]].
   */
  def copyPropagation(method: MethodNode, owner: InternalName): Boolean = {
    AsmAnalyzer.sizeOKForAliasing(method) && {
      var changed = false
      val numParams = parametersSize(method)
      lazy val aliasAnalysis = new BasicAliasingAnalyzer(method, owner)

      // Remember locals that are used in a `LOAD` instruction. Assume a program has two LOADs:
      //
      //   ...
      //   LOAD 3  // aliases of 3 here: <3>
      //   ...
      //   LOAD 1  // aliases of 1 here: <1, 3>
      //
      // In this example, we should change the second load from 1 to 3, which might render the
      // local variable 1 unused.
      val knownUsed = new Array[Boolean](BackendUtils.maxLocals(method))

      def usedOrMinAlias(it: IntIterator, init: Int): Int = {
        if (knownUsed(init)) init
        else {
          var r = init
          while (it.hasNext) {
            val n = it.next()
            // knownUsed.length is the number of locals, `n` may be a stack slot
            if (n < knownUsed.length && knownUsed(n)) return n
            if (n < r) r = n
          }
          r
        }
      }

      val it = method.instructions.iterator
      while (it.hasNext) it.next() match {
        case vi: VarInsnNode if vi.`var` >= numParams && isLoad(vi) =>
          val aliases = aliasAnalysis.frameAt(vi).asInstanceOf[AliasingFrame[_]].aliasesOf(vi.`var`)
          if (aliases.size > 1) {
            val alias = usedOrMinAlias(aliases.iterator, vi.`var`)
            if (alias != -1) {
              changed = true
              vi.`var` = alias
            }
          }
          knownUsed(vi.`var`) = true

        case _ =>
      }

      changed
    }
  }

  /**
   * Eliminate `xSTORE` instructions that have no consumer. If the instruction can be completely
   * eliminated, it is replaced by a POP. The [[eliminatePushPop]] cleans up unnecessary POPs.
   *
   * Also rewrites some intrinsics (done here because a ProdCons analysis is available):
   *   - `ClassTag(classOf[X]).newArray` is rewritten to `new Array[X]`
   *
   * Finally there's an interesting special case that complements the inliner heuristics. After
   * the rewrite above, if the `new Array[X]` is used in a `ScalaRuntime.array_apply/update` call,
   * inline that method. These methods have a big pattern match for all primitive array types, and
   * we only inline them if we statically know the array type. In this case, all the non-matching
   * branches are later eliminated by `eliminateRedundantCastsAndRewriteSomeIntrinsics`.
   *
   * Note that an `ASOTRE` can not always be eliminated: it removes a reference to the object that
   * is currently stored in that local, which potentially frees it for GC (scala/bug#5313). Therefore
   * we replace such stores by `POP; ACONST_NULL; ASTORE x` - except if the store precedes an
   * `xRETURN`, in which case it can be removed.
   *
   * Returns (staleStoreRemoved, intrinsicRewritten, callInlined).
   */
  def eliminateStaleStoresAndRewriteSomeIntrinsics(method: MethodNode, owner: InternalName): (Boolean, Boolean, Boolean) = {
    if (!AsmAnalyzer.sizeOKForSourceValue(method)) (false, false, false) else {
      lazy val prodCons = new ProdConsAnalyzer(method, owner)
      def hasNoCons(varIns: AbstractInsnNode, slot: Int) = prodCons.consumersOfValueAt(varIns.getNext, slot).isEmpty

      def popFor(vi: VarInsnNode): AbstractInsnNode = getPop(if (isSize2LoadOrStore(vi.getOpcode)) 2 else 1)

      // ASTORE insn that have no consumer.
      //   - if the local is not live, the store is replaced by POP
      //   - otherwise, pop the argument value and store NULL instead. Unless the boolean field is
      //     `true`: then the store argument is already known to be ACONST_NULL.
      val toNullOut = mutable.Map.empty[VarInsnNode, Boolean]

      val toReplace = mutable.Map.empty[AbstractInsnNode, List[AbstractInsnNode]]

      val returns = mutable.Set.empty[AbstractInsnNode]

      val toInline = mutable.Set.empty[MethodInsnNode]

      // `true` for variables that are known to be live and hold non-primitives
      val liveRefVars = new Array[Boolean](BackendUtils.maxLocals(method))

      val firstLocalIndex = parametersSize(method)

      val it = method.instructions.iterator
      while (it.hasNext) it.next() match {
        case vi: VarInsnNode if isStore(vi) && hasNoCons(vi, vi.`var`) =>
          val canElim = vi.getOpcode != ASTORE || {
            val currentFieldValueProds = prodCons.initialProducersForValueAt(vi, vi.`var`)
            currentFieldValueProds.size == 1 && (currentFieldValueProds.head match {
              case ParameterProducer(0) => !isStaticMethod(method) // current field value is `this`, which won't be gc'd anyway
              case _: UninitializedLocalProducer => true // field is not yet initialized, so current value cannot leak
              case _ => false
            })
          }
          if (canElim) toReplace(vi) = List(popFor(vi))
          else {
            val prods = prodCons.producersForValueAt(vi, prodCons.frameAt(vi).stackTop)
            val isStoreNull = prods.size == 1 && prods.head.getOpcode == ACONST_NULL
            toNullOut(vi) = isStoreNull
          }

        case ii: IincInsnNode if hasNoCons(ii, ii.`var`) =>
          toReplace(ii) = Nil

        case vi: VarInsnNode =>
          val opc = vi.getOpcode
          val markAsLive = opc == ALOAD || opc == ASTORE && (
            // a store makes the variable live if it's a parameter, or if a non-null value if stored
            vi.`var` < firstLocalIndex || prodCons.initialProducersForInputsOf(vi).exists(_.getOpcode != ACONST_NULL)
          )
          if (markAsLive)
            liveRefVars(vi.`var`) = true

        case mi: MethodInsnNode =>
          // rewrite `ClassTag(classOf[X]).newArray` to `new Array[X]`
          val newArrayCls = BackendUtils.classTagNewArrayArg(mi, prodCons)
          if (newArrayCls != null) {
            val receiverProds = prodCons.producersForValueAt(mi, prodCons.frameAt(mi).stackTop - 1)
            if (receiverProds.size == 1) {
              toReplace(receiverProds.head) = List(receiverProds.head, getPop(1))
              toReplace(mi) = List(new TypeInsnNode(ANEWARRAY, newArrayCls))
              toInline ++= prodCons.ultimateConsumersOfOutputsFrom(mi).collect({case i if isRuntimeArrayLoadOrUpdate(i) => i.asInstanceOf[MethodInsnNode]})
            }
          }

        case insn =>
          if (isReturn(insn)) returns += insn
      }

      def isTrailing(insn: AbstractInsnNode) = insn != null && {
        import scala.tools.asm.tree.AbstractInsnNode._
        insn.getType match {
          case METHOD_INSN | INVOKE_DYNAMIC_INSN | JUMP_INSN | TABLESWITCH_INSN | LOOKUPSWITCH_INSN => false
          case _ => true
        }
      }

      // stale stores that precede a return can be removed, there's no need to null them out. the
      // references are released for gc when the method returns. this also cleans up unnecessary
      // `ACONST_NULL; ASTORE x` created by the inliner (for locals of the inlined method).
      for (ret <- returns) {
        var i = ret
        while (isTrailing(i)) {
          if (i.getType == AbstractInsnNode.VAR_INSN) {
            val vi = i.asInstanceOf[VarInsnNode]
            if (toNullOut.remove(vi).nonEmpty)
              toReplace(vi) = List(popFor(vi))
          }
          i = i.getPrevious
        }
      }

      var staleStoreRemoved = toNullOut.nonEmpty
      var intrinsicRewritten = false
      val callInlined = toInline.nonEmpty

      for ((i, nis) <- toReplace) {
        i.getType match {
          case AbstractInsnNode.VAR_INSN | AbstractInsnNode.IINC_INSN => staleStoreRemoved = true
          case AbstractInsnNode.METHOD_INSN => intrinsicRewritten = true
          case _ =>
        }
        // the original instruction `i` may appear (once) in `nis`.
        var insertBefore = i
        var insertAfter: AbstractInsnNode = null
        for (ni <- nis) {
          if (ni eq i) {
            insertBefore = null
            insertAfter = i
          } else if (insertBefore != null)
            method.instructions.insertBefore(insertBefore, ni)
          else {
            method.instructions.insert(insertAfter, ni)
            insertAfter = ni
          }
        }
        if (insertBefore != null)
          method.instructions.remove(i)
      }

      for ((vi, isStoreNull) <- toNullOut) {
        if (!liveRefVars(vi.`var`)) method.instructions.set(vi, popFor(vi)) // can drop `ASTORE x` where x has only dead stores
        else {
          if (!isStoreNull) {
            val prev = vi.getPrevious
            method.instructions.insert(prev, new InsnNode(ACONST_NULL))
            method.instructions.insert(prev, getPop(1))
          }
        }
      }

      if (toInline.nonEmpty) {
        import postProcessor._
        val methodCallsites = callGraph.callsites(method)
        var css = toInline.flatMap(methodCallsites.get).toList.sorted(inliner.callsiteOrdering)
        while (css.nonEmpty) {
          val cs = css.head
          css = css.tail
          inliner.inlineCallsite(cs, None, updateCallGraph = css.isEmpty)
        }
      }

      (staleStoreRemoved, intrinsicRewritten, callInlined)
    }
  }

  /**
   * When a POP instruction has a single producer, remove the POP and eliminate the producer by
   * bubbling up the POPs. For example, given
   *   ILOAD 1; ILOAD 2; IADD; POP
   * we first eliminate the POP, then the IADD, then its inputs, so the entire sequence goes away.
   * If a producer cannot be eliminated (need to keep side-effects), a POP is inserted.
   *
   * A special case eliminates the creation of unused objects with side-effect-free constructors:
   *   NEW scala/Tuple1; DUP; ALOAD 0; INVOKESPECIAL scala/Tuple1.<init>; POP
   * The POP has a single producer (the DUP), it's easy to eliminate these two. A special case
   * is needed to eliminate the INVOKESPECIAL and NEW.
   *
   * Returns (pushPopChanged, castAdded, nullCheckAdded)
   */
  def eliminatePushPop(method: MethodNode, owner: InternalName): (Boolean, Boolean, Boolean) = {
    if (!AsmAnalyzer.sizeOKForSourceValue(method)) (false, false, false) else {
      // A queue of instructions producing a value that has to be eliminated. If possible, the
      // instruction (and its inputs) will be removed, otherwise a POP is inserted after
      val queue = mutable.Queue.empty[ProducedValue]
      // Contains constructor invocations for values that can be eliminated if unused.
      val sideEffectFreeConstructorCalls = mutable.ArrayBuffer.empty[MethodInsnNode]

      // instructions to remove (we don't change the bytecode while analyzing it. this allows
      // running the ProdConsAnalyzer only once.)
      val toRemove = mutable.Set.empty[AbstractInsnNode]
      // instructions to insert before some instruction
      val toInsertBefore = mutable.Map.empty[AbstractInsnNode, List[AbstractInsnNode]]
      // an instruction to insert after some instruction
      val toInsertAfter = mutable.Map.empty[AbstractInsnNode, AbstractInsnNode]

      var castAdded = false
      var nullCheckAdded = false

      lazy val prodCons = new ProdConsAnalyzer(method, owner)

      /*
       * Returns the producers for the stack value `inputSlot` consumed by `cons`, if the consumer
       * instruction is the only consumer for all of these producers.
       *
       * If a producer has multiple consumers, or the value is the caught exception in a catch
       * block, this method returns Set.empty.
       */
      def producersIfSingleConsumer(cons: AbstractInsnNode, inputSlot: Int): Set[AbstractInsnNode] = {
        /*
         * True if the values produced by `prod` are all the same. Most instructions produce a single
         * value. DUP and DUP2 (with a size-2 input) produce two equivalent values. However, there
         * are some exotic instructions that produce multiple non-equal values (DUP_X1, SWAP, ...).
         *
         * Assume we have `DUP_X2; POP`. In order to remove the `POP` we need to change the DUP_X2
         * into something else, which is not straightforward.
         *
         * Since scalac never emits any of those exotic bytecodes, we don't optimize them.
         */
        def producerHasSingleOutput(prod: AbstractInsnNode): Boolean = prod match {
          case _: ExceptionProducer[_] | _: UninitializedLocalProducer =>
            // POP of an exception in a catch block cannot be removed. For an uninitialized local,
            // there should not be a consumer. We are conservative and include it here, so the
            // producer would not be removed.
            false

          case _: ParameterProducer =>
            true

          case _ => (prod.getOpcode: @switch) match {
            case DUP => true
            case DUP2 => prodCons.frameAt(prod).peekStack(0).getSize == 2
            case _ => InstructionStackEffect.prod(InstructionStackEffect.forAsmAnalysis(prod, prodCons.frameAt(prod))) == 1
          }
        }

        val prods = prodCons.producersForValueAt(cons, inputSlot)
        val singleConsumer = prods forall { prod =>
          producerHasSingleOutput(prod) && {
            // for DUP / DUP2, we only consider the value that is actually consumed by cons
            val conss = prodCons.consumersOfValueAt(prod.getNext, inputSlot)
            conss.size == 1 && conss.head == cons
          }
        }
        if (singleConsumer) prods else Set.empty
      }

      /*
       * For a POP instruction that is the single consumer of its producers, remove the POP and
       * enqueue the producers.
       */
      def handleInitialPop(pop: AbstractInsnNode): Unit = {
        val prods = producersIfSingleConsumer(pop, prodCons.frameAt(pop).stackTop)
        if (prods.nonEmpty) {
          toRemove += pop
          val size = if (pop.getOpcode == POP2) 2 else 1
          queue ++= prods.map(ProducedValue(_, size))
        }
      }

      /*
       * Traverse the method in its initial state and collect all POP instructions and side-effect
       * free constructor invocations that can be eliminated.
       */
      def collectInitialPopsAndPureConstrs(): Unit = {
        val it = method.instructions.iterator
        while (it.hasNext) {
          val insn = it.next()
          (insn.getOpcode: @switch) match {
            case POP | POP2 =>
              handleInitialPop(insn)

            case INVOKESPECIAL =>
              val mi = insn.asInstanceOf[MethodInsnNode]
              if (isSideEffectFreeConstructorCall(mi)) sideEffectFreeConstructorCalls += mi

            case _ =>
          }
        }
      }

      /*
       * Eliminate the `numArgs` inputs of the instruction `prod` (which was eliminated). For
       * each input value
       *   - if the `prod` instruction is the single consumer, enqueue the producers of the input
       *   - otherwise, insert a POP instruction to POP the input value
       */
      def handleInputs(prod: AbstractInsnNode, numArgs: Int): Unit = {
        val frame = prodCons.frameAt(prod)
        val pops = mutable.ListBuffer.empty[InsnNode]
        @tailrec def handle(stackOffset: Int): Unit = {
          if (stackOffset >= 0) {
            val prods = producersIfSingleConsumer(prod, frame.stackTop - stackOffset)
            val nSize = frame.peekStack(stackOffset).getSize
            if (prods.isEmpty) pops += getPop(nSize)
            else queue ++= prods.map(ProducedValue(_, nSize))
            handle(stackOffset - 1)
          }
        }
        handle(numArgs - 1) // handle stack offsets (numArgs - 1) to 0
        if (pops.nonEmpty) toInsertBefore(prod) = pops.toList
      }

      /* Eliminate LMF `indy` and its inputs. */
      def handleClosureInst(indy: InvokeDynamicInsnNode): Unit = {
        toRemove += indy
        callGraph.removeClosureInstantiation(indy, method)
        removeIndyLambdaImplMethod(owner, method, indy)
        handleInputs(indy, Type.getArgumentTypes(indy.desc).length)
      }

      def runQueue(): Unit = while (queue.nonEmpty) {
        val ProducedValue(prod, size) = queue.dequeue()

        def prodString = s"Producer ${AsmUtils textify prod}@${method.instructions.indexOf(prod)}\n${AsmUtils textify method}"
        def popAfterProd(): Unit = toInsertAfter(prod) = getPop(size)

        (prod.getOpcode: @switch) match {
          case ACONST_NULL | ICONST_M1 | ICONST_0 | ICONST_1 | ICONST_2 | ICONST_3 | ICONST_4 | ICONST_5 | LCONST_0 | LCONST_1 | FCONST_0 | FCONST_1 | FCONST_2 | DCONST_0 | DCONST_1 |
               BIPUSH | SIPUSH | ILOAD | LLOAD | FLOAD | DLOAD | ALOAD=>
            toRemove += prod

          case opc @ (DUP | DUP2) =>
            assert(opc != 2 || size == 2, s"DUP2 for two size-1 values; $prodString") // ensured in method `producerHasSingleOutput`
            if (toRemove(prod))
            // the DUP is already scheduled for removal because one of its consumers is a POP.
            // now the second consumer is also a POP, so we need to eliminate the DUP's input.
              handleInputs(prod, 1)
            else
              toRemove += prod

          case DUP_X1 | DUP_X2 | DUP2_X1 | DUP2_X2 | SWAP =>
            // these are excluded in method `producerHasSingleOutput`
            assert(false, s"Cannot eliminate value pushed by an instruction with multiple output values; $prodString")

          case IDIV | LDIV | IREM | LREM =>
            popAfterProd() // keep potential division by zero

          case IADD | LADD | FADD | DADD | ISUB | LSUB | FSUB | DSUB | IMUL | LMUL | FMUL | DMUL | FDIV | DDIV | FREM | DREM |
               LSHL | LSHR | LUSHR |
               IAND | IOR | IXOR | LAND | LOR | LXOR |
               LCMP | FCMPL | FCMPG | DCMPL | DCMPG =>
            toRemove += prod
            handleInputs(prod, 2)

          case INEG | LNEG | FNEG | DNEG |
               I2L | I2F | I2D | L2I | L2F | L2D | F2I | F2L | F2D | D2I | D2L | D2F | I2B | I2C | I2S =>
            toRemove += prod
            handleInputs(prod, 1)

          case GETFIELD | GETSTATIC =>
            if (isBoxedUnit(prod) || isModuleLoad(prod, modulesAllowSkipInitialization)) toRemove += prod
            else popAfterProd() // keep potential class initialization (static field) or NPE (instance field)

          case INVOKEVIRTUAL | INVOKESPECIAL | INVOKESTATIC | INVOKEINTERFACE =>
            val methodInsn = prod.asInstanceOf[MethodInsnNode]
            if (isSideEffectFreeCall(methodInsn)) {
              toRemove += prod
              callGraph.removeCallsite(methodInsn, method)
              val receiver = if (methodInsn.getOpcode == INVOKESTATIC) 0 else 1
              handleInputs(prod, Type.getArgumentTypes(methodInsn.desc).length + receiver)
            } else if (isScalaUnbox(methodInsn)) {
              val tp = primitiveAsmTypeToBType(Type.getReturnType(methodInsn.desc))
              val boxTp = bTypes.coreBTypes.boxedClassOfPrimitive(tp)
              toInsertBefore(methodInsn) = List(new TypeInsnNode(CHECKCAST, boxTp.internalName), new InsnNode(POP))
              toRemove += prod
              callGraph.removeCallsite(methodInsn, method)
              castAdded = true
            } else if (isJavaUnbox(methodInsn)) {
              val nullCheck = mutable.ListBuffer.empty[AbstractInsnNode]
              val nonNullLabel = newLabelNode
              nullCheck += new JumpInsnNode(IFNONNULL, nonNullLabel)
              nullCheck += new InsnNode(ACONST_NULL)
              nullCheck += new InsnNode(ATHROW)
              nullCheck += nonNullLabel
              toInsertBefore(methodInsn) = nullCheck.toList
              toRemove += prod
              callGraph.removeCallsite(methodInsn, method)
              method.maxStack = math.max(BackendUtils.maxStack(method), prodCons.frameAt(methodInsn).getStackSize + 1)
              nullCheckAdded = true
            } else
              popAfterProd()

          case INVOKEDYNAMIC =>
            prod match {
              case LambdaMetaFactoryCall(indy, _, _, _, _) => handleClosureInst(indy)
              case _ => popAfterProd()
            }

          case NEW =>
            if (isNewForSideEffectFreeConstructor(prod)) toRemove += prod
            else popAfterProd()

          case LDC =>
            prod.asInstanceOf[LdcInsnNode].cst match {
            case _: java.lang.Integer | _: java.lang.Float | _: java.lang.Long | _: java.lang.Double | _: String =>
              toRemove += prod

            case _ =>
              if (compilerSettings.optAllowSkipClassLoading) toRemove += prod
              else popAfterProd()
          }

          case MULTIANEWARRAY =>
            toRemove += prod
            handleInputs(prod, prod.asInstanceOf[MultiANewArrayInsnNode].dims)

          case _ =>
            popAfterProd()
        }
      }

      // there are two cases when we can eliminate a constructor call:
      //   - NEW T; INVOKESPECIAL T.<init> -- there's no DUP, the new object is consumed only by the constructor)
      //   - NEW T; DUP; INVOKESPECIAL T.<init>, where the DUP will be removed
      def eliminateUnusedPureConstructorCalls(): Boolean = {
        var changed = false

        def removeConstructorCall(mi: MethodInsnNode): Unit = {
          toRemove += mi
          callGraph.removeCallsite(mi, method)
          sideEffectFreeConstructorCalls -= mi
          changed = true
        }

        for (mi <- sideEffectFreeConstructorCalls.toList) { // toList to allow removing elements while traversing
        val frame = prodCons.frameAt(mi)
          val stackTop = frame.stackTop
          val numArgs = Type.getArgumentTypes(mi.desc).length
          val receiverProds = producersIfSingleConsumer(mi, stackTop - numArgs)
          if (receiverProds.size == 1) {
            val receiverProd = receiverProds.head
            if (receiverProd.getOpcode == NEW) {
              removeConstructorCall(mi)
              handleInputs(mi, numArgs + 1) // removes the producers of args and receiver
            } else if (receiverProd.getOpcode == DUP && toRemove.contains(receiverProd)) {
              val dupProds = producersIfSingleConsumer(receiverProd, prodCons.frameAt(receiverProd).stackTop)
              if (dupProds.size == 1 && dupProds.head.getOpcode == NEW) {
                removeConstructorCall(mi)
                handleInputs(mi, numArgs) // removes the producers of args. the producer of the receiver is DUP and already in toRemove.
                queue += ProducedValue(dupProds.head, 1) // removes the NEW (which is NOT the producer of the receiver!)
              }
            }
          }
        }
        changed
      }

      collectInitialPopsAndPureConstrs()

      // eliminating producers enables eliminating unused constructor calls (when a DUP gets removed).
      // vice-versa, eliminating a constructor call adds producers of constructor parameters to the queue.
      // so the two run in a loop.
      runQueue()
      while (eliminateUnusedPureConstructorCalls())
        runQueue()

      var changed = false
      toInsertAfter foreach {
        case (target, insn) =>
          nextExecutableInstructionOrLabel(target) match {
            case Some(next) if insn.getType == AbstractInsnNode.INSN && next.getOpcode == insn.getOpcode && toRemove(next) =>
              // Inserting and removing a POP at the same place should not enable `changed`. This happens
              // when a POP directly follows a producer that cannot be eliminated, e.g. INVOKESTATIC A.m ()I; POP
              // The POP is initially added to `toRemove`, and the `INVOKESTATIC` producer is added to the queue.
              // Because the producer cannot be elided, a POP is added to `toInsertAfter`.
              toRemove -= next

            case _ =>
              changed = true
              method.instructions.insert(target, insn)
          }
      }
      toInsertBefore foreach {
        case (target, insns) =>
          changed = true
          insns.foreach(method.instructions.insertBefore(target, _))
      }
      toRemove foreach { insn =>
        changed = true
        method.instructions.remove(insn)
      }
      (changed, castAdded, nullCheckAdded)
    }
  }

  case class ProducedValue(producer: AbstractInsnNode, size: Int) {
    override def toString = s"<${AsmUtils textify producer}>"
  }

  /**
   * Remove `xSTORE n; xLOAD n` pairs if
   *   - the local variable n is not used anywhere else in the method (1), and
   *   - there are no executable instructions and no live labels (jump targets) between the two (2)
   *
   * Note: store-load pairs that cannot be eliminated could be replaced by `DUP; xSTORE n`, but
   * that's just cosmetic and doesn't help for anything.
   *
   * (1) This could be made more precise by running a prodCons analysis and checking that the load
   * is the only user of the store. Then we could eliminate the pair even if the variable is live
   * (except for ASTORE, scala/bug#5313). Not needing an analyzer is more efficient, and catches most
   * cases.
   *
   * (2) The implementation uses a conservative estimation for liveness (if some instruction uses
   * local n, then n is considered live in the entire method). In return, it doesn't need to run an
   * Analyzer on the method, making it more efficient.
   *
   * This method also removes `ACONST_NULL; ASTORE n` if the local n is not live. This pattern is
   * introduced by [[eliminateStaleStoresAndRewriteSomeIntrinsics]].
   *
   * The implementation is a little tricky to support the following case:
   *   ISTORE 1; ISTORE 2; ILOAD 2; ACONST_NULL; ASTORE 3; ILOAD 1
   * The outer store-load pair can be removed if two the inner pairs can be.
   */
  def eliminateStoreLoad(method: MethodNode): Boolean = {
    // TODO: use copyProp once we have cached analyses? or is the analysis invalidated anyway because instructions are deleted / changed?
    // if we cache them anyway, we can use an analysis if it exists in the cache, and skip otherwise.
    val removePairs = mutable.Set.empty[RemovePair]
    val liveVars = new Array[Boolean](BackendUtils.maxLocals(method))
    val liveLabels = mutable.Set.empty[LabelNode]

    def mkRemovePair(store: VarInsnNode, other: AbstractInsnNode, depends: List[RemovePairDependency]): RemovePair = {
      val r = RemovePair(store, other, depends)
      removePairs += r
      r
    }

    def registerLiveVarsLabels(insn: AbstractInsnNode): Unit = insn match {
      case vi: VarInsnNode => liveVars(vi.`var`) = true
      case ii: IincInsnNode => liveVars(ii.`var`) = true
      case j: JumpInsnNode => liveLabels += j.label
      case s: TableSwitchInsnNode => liveLabels += s.dflt; liveLabels ++= s.labels.asScala
      case s: LookupSwitchInsnNode => liveLabels += s.dflt; liveLabels ++= s.labels.asScala
      case _ =>
    }

    val pairStartStack = new mutable.Stack[(AbstractInsnNode, mutable.ListBuffer[RemovePairDependency])]

    def push(insn: AbstractInsnNode): Unit = {
      pairStartStack push ((insn, mutable.ListBuffer.empty))
    }

    def addDepends(dependency: RemovePairDependency): Unit = if (pairStartStack.nonEmpty) {
      val (_, depends) = pairStartStack.top
      depends += dependency
    }

    def completesStackTop(load: AbstractInsnNode) = isLoad(load) && pairStartStack.nonEmpty && {
      pairStartStack.top match {
        case (store: VarInsnNode, _) => store.`var` == load.asInstanceOf[VarInsnNode].`var`
        case _ => false
      }
    }

    /*
     * Try to pair `insn` with its correspondent on the stack
     *   - if the stack top is a store and `insn` is a corresponding load, create a pair
     *   - otherwise, check the two top stack values for `null; store`. if it matches, create
     *     a pair and continue pairing `insn` on the remaining stack
     *   - otherwise, empty the stack and mark the local variables in it live
     */
    def tryToPairInstruction(insn: AbstractInsnNode): Unit = {
      @tailrec def emptyStack(): Unit = if (pairStartStack.nonEmpty) {
        registerLiveVarsLabels(pairStartStack.pop()._1)
        emptyStack()
      }

      @tailrec def tryPairing(): Unit = {
        if (completesStackTop(insn)) {
          val (store: VarInsnNode, depends) = pairStartStack.pop(): @unchecked
          addDepends(mkRemovePair(store, insn, depends.toList))
        } else if (pairStartStack.nonEmpty) {
          val (top, topDepends) = pairStartStack.pop()
          if (pairStartStack.nonEmpty) {
            (pairStartStack.top, top) match {
              case ((ldNull: InsnNode, depends), store: VarInsnNode) if ldNull.getOpcode == ACONST_NULL && store.getOpcode == ASTORE =>
                pairStartStack.pop()
                addDepends(mkRemovePair(store, ldNull, depends.toList))
                // example: store; (null; store;) (store; load;) load
                //                         s1^     ^^^^^p1^^^^^        // p1 is added to s1's depends
                // then:    store; (null; store;) load
                //           s2^    ^^^^p2^^^^^                        // p1 and p2 are added to s2's depends
                topDepends foreach addDepends
                tryPairing()

              case _ =>
                // empty the stack - a non-matching insn was found, cannot create any pairs to remove
                registerLiveVarsLabels(insn)
                registerLiveVarsLabels(top)
                emptyStack()
            }
          } else {
            // stack only has one element
            registerLiveVarsLabels(insn)
            registerLiveVarsLabels(top)
          }
        } else {
          // stack is empty already
          registerLiveVarsLabels(insn)
        }
      }

      tryPairing()
    }


    var insn = method.instructions.getFirst

    @tailrec def advanceToNextExecutableOrLabel(): Unit = {
      insn = insn.getNext
      if (insn != null && !isExecutable(insn) && !insn.isInstanceOf[LabelNode]) advanceToNextExecutableOrLabel()
    }

    while (insn != null) {
      insn match {
        case _ if insn.getOpcode == ACONST_NULL          => push(insn)
        case vi: VarInsnNode if isStore(vi)              => push(insn)
        case label: LabelNode if pairStartStack.nonEmpty => addDepends(LabelNotLive(label))
        case _                                           => tryToPairInstruction(insn)
      }
      advanceToNextExecutableOrLabel()
    }

    // elide RemovePairs that depend on live labels or other RemovePair that have to be elided.
    // example:  store 1; store 2; label x; load 2; load 1
    // if x is live, the inner pair has to be elided, causing the outer pair to be elided too.

    var doneEliding = false

    def elide(removePair: RemovePair) = {
      doneEliding = false
      liveVars(removePair.store.`var`) = true
      removePairs -= removePair
    }

    while (!doneEliding) {
      doneEliding = true
      for (removePair <- removePairs.toList) {
        val slot = removePair.store.`var`
        if (liveVars(slot)) elide(removePair)
        else removePair.depends foreach {
          case LabelNotLive(label) => if (liveLabels(label)) elide(removePair)
          case other: RemovePair => if (!removePairs(other)) elide(removePair)
        }
      }
    }

    for (removePair <- removePairs) {
      method.instructions.remove(removePair.store)
      method.instructions.remove(removePair.other)
    }

    val changed = removePairs.nonEmpty
    changed
  }
}

sealed trait RemovePairDependency
case class RemovePair(store: VarInsnNode, other: AbstractInsnNode, depends: List[RemovePairDependency]) extends RemovePairDependency {
  override def toString = s"<${AsmUtils textify store},${AsmUtils textify other}> [$depends]"
}
case class LabelNotLive(label: LabelNode) extends RemovePairDependency
