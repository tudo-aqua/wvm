/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2024-2024 The While* Authors
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

package tools.aqua.wvm.machine

import tools.aqua.wvm.analysis.hoare.SMTSolver
import tools.aqua.wvm.analysis.semantics.BooleanExpressionError
import java.math.BigInteger
import java.util.Scanner
import tools.aqua.wvm.language.*

data class Context(
    val scope: Scope,
    val program: List<Statement>,
    val pre: BooleanExpression = True,
    val post: BooleanExpression = True,
    var input: Scanner? = null,
) {
  fun execute(verbose: Boolean,
              symbolic: Boolean,
              output: Output = Output(),
              maxSteps: Int = -1): Pair<ExecutionTree, Set<ExecutionTree>> {
    val mem = initMemForScope(symbolic)
    val pathConstraints = pre.evaluate(scope,mem, True)
    if (pathConstraints.any{it is BooleanExpressionError })
      throw Exception("Path constraint cannot be evaluated ${pathConstraints.first{it is BooleanExpressionError }}")
    val pc = pathConstraints.map { it.result }.reduce{ acc, booleanExpression -> And(acc, booleanExpression) }
    val initialCfg = Configuration(SequenceOfStatements(program), scope, mem,false, pc)
    val root = ExecutionTree(mutableMapOf(), initialCfg)
    var wl = mutableListOf(root)

    while (wl.isNotEmpty()) {
      val current = wl.first()
      wl = wl.drop(1).toMutableList()
      if (current.cfg.isFinal())
        continue
      val cfg = current.cfg
      val stepCount = current.stepCount
      if (maxSteps in 1 ..< stepCount+1) {
        output.println("Terminated after ${maxSteps} steps.")
        continue
      }
      if (cfg.error) {
        output.println("Terminated abnormally.")
        continue
      }
      val steps = cfg.statements.head().execute(cfg, input, symbolic)
      for(step in steps) {
        if (step.result.output != null && !symbolic && !verbose) {
          output.println(step.result.output)
        }
        val nextCfg = step.result.dst
        val nextExecTree = ExecutionTree(next = mutableMapOf(), nextCfg, stepCount+1, step.result.output)
        current.next.put(step, nextExecTree)
        wl.addLast(nextExecTree)
      }

    }

    if (verbose) println("end.")
    return Pair(root, root.unsafePaths(post))
  }

  private fun initMemForScope(symbolic: Boolean): Memory<ArithmeticExpression> {
    var mem = Memory(Array(scope.size) { NumericLiteral(BigInteger.ZERO) as ArithmeticExpression })
    scope.symbols.values
      .filter { it.size > 1 }
      .forEach { mem = mem.write(it.address, NumericLiteral(it.address.toBigInteger().plus(BigInteger.ONE))) }
    if (symbolic) {
      for ((v,info) in scope.symbols) {
        val base = info.address
        for (offset in 0 ..info.size - 1) {
          val symbolicValue = "$v$offset"
          if (info.size == 1 || offset != 0) {
            mem = mem.write(base + offset, ValAtAddr(Variable(symbolicValue)))
          }
        }
      }
    }
//    scope.symbols.forEach {
//      println("${it.key} -> ${it.value.type} of size ${it.value.size} at ${it.value.address}")
//    }
//    println(mem)
    return mem
  }

  override fun toString(): String {
    return "$scope\n $program"
  }
}
