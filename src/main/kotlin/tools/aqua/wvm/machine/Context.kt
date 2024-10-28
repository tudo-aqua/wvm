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
    var input: Scanner? = null
) {
  fun execute(verbose: Boolean, output: Output = Output(), maxSteps: Int = -1): List<StatementApp> {
    var cfg = Configuration(SequenceOfStatements(program), scope, initMemForScope())
    var trace = listOf<StatementApp>()
    var stepCount = 0
    if (verbose) println(cfg)
    while (!cfg.isFinal()) {
      stepCount++
      if (maxSteps in 1 ..< stepCount) {
        output.println("Terminated after ${maxSteps} steps.")
        break
      }
      val step = cfg.statements.head().execute(cfg, input)
      if (step.result.output != null) {
        output.println(step.result.output)
      }
      trace = trace + step
      cfg = step.result.dst
      if (verbose) println(cfg)
    }
    if (cfg.error) {
      output.println("Terminated abnormally.")
    }
    if (verbose) println("end.")
    return trace
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
