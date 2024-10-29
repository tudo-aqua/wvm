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

import java.math.BigInteger
import java.util.Scanner
import tools.aqua.wvm.analysis.semantics.StatementApp
import tools.aqua.wvm.language.*

data class Context(
    val scope: Scope,
    val program: List<Statement>,
    val pre: BooleanExpression = True,
    val post: BooleanExpression = True,
    var input: Scanner? = null,
    val symbolic: Boolean = false
) {
  fun execute(verbose: Boolean, output: Output = Output(), maxSteps: Int = -1): ExecutionTree {
    val initialCfg = Configuration(SequenceOfStatements(program), scope, initMemForScope())
    val root = ExecutionTree(mutableMapOf(), initialCfg)
    var wl = mutableListOf(root)

    if (verbose) println(initialCfg)
    while (wl.isNotEmpty()) {
      val current = wl.first()
      wl = wl.drop(1).toMutableList()
      if (current.cfg.isFinal())
        break
      val cfg = current.cfg
      val stepCount = current.stepCount
      if (maxSteps in 1 ..< stepCount+1) {
        output.println("Terminated after ${maxSteps} steps.")
        break
      }
      if (cfg.error) {
        output.println("Terminated abnormally.")
        break
      }
      val steps = listOf(cfg.statements.head().execute(cfg, input))
      for(step in steps) {
        if (step.result.output != null) {
          output.println(step.result.output)
        }
        val nextCfg = step.result.dst
        val nextExecTree = ExecutionTree(next = mutableMapOf(), nextCfg, stepCount+1)
        current.next.put(step, nextExecTree)
        wl.addLast(nextExecTree)
        if (verbose) println(nextCfg)
      }

    }

    if (verbose) println("end.")
    return root
  }

  private fun initMemForScope(): Memory<ArithmeticExpression> {
    var mem = Memory(Array(scope.size) { NumericLiteral(BigInteger.ZERO) as ArithmeticExpression })
    scope.symbols.values
        .filter { it.size > 1 }
        .forEach { mem = mem.write(it.address, NumericLiteral(it.address.toBigInteger().plus(BigInteger.ONE))) }
    return mem
  }

  override fun toString(): String {
    return "$scope\n $program"
  }
}
