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

package tools.aqua.wvm.language

import java.math.BigInteger
import java.util.Scanner
import kotlin.random.Random
import tools.aqua.wvm.analysis.semantics.*
import tools.aqua.wvm.externCounter
import tools.aqua.wvm.machine.Configuration

interface PrintWithIndentation {
  fun toIndentedString(indent: String): String
}

sealed interface Statement : PrintWithIndentation {
  fun execute(cfg: Configuration, input: Scanner?, symbolic: Boolean): List<StatementApp>
}

data class Assignment(val addr: AddressExpression, val expr: ArithmeticExpression) : Statement {

  override fun execute(cfg: Configuration,
                       input: Scanner?,
                       symbolic: Boolean): List<StatementApp> {
    val a = addr.evaluate(cfg.scope, cfg.memory)
    val e = expr.evaluate(cfg.scope, cfg.memory)

    if (a is Error)
        return listOf(NestedStatementError(
            "AssErr",
            a,
            this,
            Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true))))

    if (e is Error)
        return listOf(NestedStatementError(
            "AssErr",
            e,
            this,
            Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true))))

    return listOf(AssOk(
        a as AddressOk,
        e as ArithmeticExpressionOk,
        this,
        Transition(
            cfg,
            dst =
                Configuration(
                    SequenceOfStatements(cfg.statements.tail()),
                    cfg.scope,
                    cfg.memory.write(a.result, e.result)))))
  }

  override fun toIndentedString(indent: String) = "${indent}$addr := $expr;\n"
}

data class Swap(val left: AddressExpression, val right: AddressExpression) : Statement {

  override fun execute(cfg: Configuration,
                       input: Scanner?,
                       symbolic: Boolean): List<StatementApp> {
    val apps = mutableListOf<StatementApp>()
    for (a1 in left.evaluate(cfg.scope, cfg.memory)) {
      for (a2 in right.evaluate(cfg.scope, cfg.memory)) {
        if (a1 is Error) {
          apps.addLast(
            NestedStatementError(
              "SwapErr",
              a1,
              this,
              Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true)
              )
            )
          )
          continue
        }
        if (a2 is Error) {
          apps.addLast(
            NestedStatementError(
              "SwapErr",
              a2,
              this,
              Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true)
              )
            )
          )
          continue
        }
        val e1 = cfg.memory.read(a1.result)
        val e2 = cfg.memory.read(a2.result)

        apps.addLast(
          SwapOk(
            a1 as AddressOk,
            a2 as AddressOk,
            this,
            Transition(
              cfg,
              dst =
              Configuration(
                SequenceOfStatements(cfg.statements.tail()),
                cfg.scope,
                cfg.memory.write(a1.result, e2).write(a2.result, e1)
              )
            )
          )
        )
      }
    }
    return apps
  }

  override fun toIndentedString(indent: String) = "${indent}swap $left and $right;\n"
}

data class Assertion(val cond: BooleanExpression) : Statement {

  override fun execute(cfg: Configuration,
                       input: Scanner?,
                       symbolic: Boolean): List<StatementApp> {
    val apps = mutableListOf<StatementApp>()
    for (b in cond.evaluate(cfg.scope, cfg.memory)) {
      if (b is Error) {
        apps.addLast(
          NestedStatementError(
            "AssertErr",
            b,
            this,
            Transition(
              cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true)
            )
          )
        )
        continue
      }
      when (b.result) {
        True ->
          apps.addLast(
            AssertOK(
              b as BooleanExpressionOk,
              Transition(
                cfg,
                dst =
                Configuration(
                  SequenceOfStatements(cfg.statements.tail()), cfg.scope, cfg.memory
                )
              )
            )
          )

        False ->
          apps.addLast(
            AssertErr(
              b as BooleanExpressionOk,
              Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, error = true)
              )
            )
          )

        else -> throw Exception("Symbolic Assertions not handled yet")
      }
    }
    return apps
  }

  override fun toIndentedString(indent: String) = "${indent}assert $cond;"
}

data class IfThenElse(
    val cond: BooleanExpression,
    val thenBlock: SequenceOfStatements,
    val elseBlock: SequenceOfStatements
) : Statement {

  override fun execute(cfg: Configuration,
                       input: Scanner?,
                       symbolic: Boolean): List<StatementApp> {
    val apps = mutableListOf<StatementApp>()
    for (b in cond.evaluate(cfg.scope, cfg.memory)) {
      if (b is Error) {
        apps.addLast(
          NestedStatementError(
            "IfErr",
            b,
            this,
            Transition(
              cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true)
            )
          )
        )
        continue
      }
      when (b.result) {
        True ->
          apps.addLast(
            IfTrue(
              b as BooleanExpressionOk,
              this,
              Transition(
                cfg,
                dst =
                Configuration(
                  concat(thenBlock.statements, cfg.statements.tail()),
                  cfg.scope,
                  cfg.memory
                )
              )
            )
          )

        False ->
          apps.addLast(
            IfFalse(
              b as BooleanExpressionOk,
              this,
              Transition(
                cfg,
                dst =
                Configuration(
                  concat(elseBlock.statements, cfg.statements.tail()),
                  cfg.scope,
                  cfg.memory
                )
              )
            )
          )

        else -> throw Exception("Symbolic If-Condition not handled yet")
      }
    }
    return apps
  }

  override fun toIndentedString(indent: String) =
      "${indent}if ($cond) {\n" +
          thenBlock.toIndentedString("  $indent") +
          "${indent}} else {" +
          elseBlock.toIndentedString("  $indent") +
          "${indent}};\n"
}

data class While(
    val head: BooleanExpression,
    val body: SequenceOfStatements,
    val invariant: BooleanExpression = True
) : Statement {

  override fun execute(cfg: Configuration,
                       input: Scanner?,
                       symbolic: Boolean): List<StatementApp> {
    val apps = mutableListOf<StatementApp>()
    for (cond in head.evaluate(cfg.scope, cfg.memory)) {
      for (invar in invariant.evaluate(cfg.scope, cfg.memory)) {

        if (cond is Error) {
          apps.addLast(
            NestedStatementError(
              "IfErr",
              cond,
              this,
              Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true)
              )
            )
          )
          continue
        }
        if (invar is Error) {
          apps.addLast(
            NestedStatementError(
              "IfErr",
              invar,
              this,
              Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true)
              )
            )
          )
          continue
        }
        when (cond.result to invar.result) {
          (True to True) ->
            apps.addLast(
              WhTrue(
                cond as BooleanExpressionOk,
                invar as BooleanExpressionOk,
                this,
                Transition(
                  cfg,
                  dst =
                  Configuration(
                    concat(body.statements, cfg.statements.statements), cfg.scope, cfg.memory
                  )
                )
              )
            )

          (False to True) ->
            apps.addLast(
              WhFalse(
                cond as BooleanExpressionOk,
                invar as BooleanExpressionOk,
                this,
                Transition(
                  cfg,
                  dst =
                  Configuration(
                    SequenceOfStatements(cfg.statements.tail()), cfg.scope, cfg.memory
                  )
                )
              )
            )

          (True to False) ->
            apps.addLast(
              WhInvar(
                cond as BooleanExpressionOk,
                invar as BooleanExpressionOk,
                this,
                Transition(
                  cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true)
                )
              )
            )

          (False to False) ->
            apps.addLast(
              WhInvar(
                cond as BooleanExpressionOk,
                invar as BooleanExpressionOk,
                this,
                Transition(
                  cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true)
                )
              )
            )

          else -> throw Exception("Symbolic If-Condition not handled yet")
        }
      }
    }
    return apps
  }

  override fun toIndentedString(indent: String) =
      "${indent}while ($head) invariant ($invariant) {\n" +
          body.toIndentedString("  $indent") +
          "${indent}};\n"
}

fun <A> List<List<A>>.multiply() : List<List<A>> {
  if (this.isEmpty())
    return listOf()

  val l = this.first()
  val ll = this.drop(1)
  if(ll.isEmpty())
    return l.map { listOf(it) }
  val acc = mutableListOf<List<A>>()
  for (e in l) {
    for (p in ll.multiply()) {
      acc.addLast(listOf(e) + p)
    }
  }
  return acc
}

data class Print(val message: String, val values: List<ArithmeticExpression>) : Statement {

  override fun execute(cfg: Configuration,
                       input: Scanner?,
                       symbolic: Boolean): List<StatementApp> {
    val valuesL = mutableListOf<MutableList<Application<ArithmeticExpression>>>()
    for (v in values) {
      valuesL.addLast(mutableListOf())
      for (e in v.evaluate(cfg.scope, cfg.memory)) {
        valuesL.last().addLast(e)
      }
    }
    val valueCombinations = valuesL.multiply()
    val apps = mutableListOf<StatementApp>()
    for (comb in valueCombinations) {
      if (comb.any { it is Error }) {
        apps.addLast(NestedStatementError(
          "PrintErr",
          comb.first { it is Error } as Error,
          this,
          Transition(
            cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true)
          )))
        continue
      }

      var out = message
      if (comb.isNotEmpty()) {
        out += comb.map { it.result }.joinToString(", ", " [", "]")
      }
      apps.addLast(
        PrintOk(
          comb.map { it as ArithmeticExpressionOk },
          this,
          Transition(
            cfg,
            output = out,
            dst =
            Configuration(SequenceOfStatements(cfg.statements.tail()), cfg.scope, cfg.memory)
          )
        )
      )
    }
    return apps
  }

  override fun toIndentedString(indent: String) =
      "print \"$message\"${values.joinToString(", ", ", ")};\n"
}

data class Havoc(
    val addr: AddressExpression,
    val lower: BigInteger = BigInteger.valueOf(-100L),
    val upper: BigInteger = BigInteger.valueOf(100)
) : Statement {
  init {
    if (lower >= upper) {
      throw IllegalArgumentException(
          "Bounds for external variables have to be ordered. Here $lower > ${upper.minus(BigInteger.ONE)}.")
    }
  }

  override fun execute(cfg: Configuration,
                       input: Scanner?,
                       symbolic: Boolean): List<StatementApp> {
    val apps = mutableListOf<StatementApp>()

    for (a in addr.evaluate(cfg.scope, cfg.memory)) {
      if (a is Error) {
        apps.addLast(
          NestedStatementError(
            "HavocErr",
            a,
            this,
            Transition(
              cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true)
            )
          )
        )
        continue
      }
      if(!symbolic) {
        val number =
          input?.nextBigInteger() ?: Random.nextLong(lower.toLong(), upper.toLong()).toBigInteger()
        if (number < lower || number > upper)
          apps.addLast(
            HavocRangeErr(
              a as AddressOk,
              this,
              Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true)
              )
            )
          )
        else
          apps.addLast(
            HavocOk(
              a as AddressOk,
              this,
              Transition(
                cfg,
                input = number,
                dst =
                Configuration(
                  SequenceOfStatements(cfg.statements.tail()),
                  cfg.scope,
                  cfg.memory.write(a.result, NumericLiteral(number))
                )
              )
            )
          )
      }
      if (symbolic) {
        val symbolName = "extern${externCounter}"
        externCounter += 1
        apps.addLast(
          HavocOk(
            a as AddressOk,
            this,
            Transition(
              cfg,
              input = null,
              dst =
              Configuration(
                SequenceOfStatements(cfg.statements.tail()),
                cfg.scope,
                cfg.memory.write(a.result, ValAtAddr(Variable(symbolName)))
              )
            )
          )
        )
      }
    }
    return apps
  }
  override fun toIndentedString(indent: String) =
      "${indent}extern $addr $lower .. ${upper.minus(BigInteger.ONE)};\n"
}

data class Fail(val message: String) : Statement {

  override fun execute(cfg: Configuration,
                       input: Scanner?,
                       symbolic: Boolean): List<StatementApp> =
      listOf(FailOk(
          this,
          Transition(
              cfg,
              output = "Fail with message: $message",
              dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true))))

  override fun toIndentedString(indent: String) = "fail \"$message\"\n"
}

data class SequenceOfStatements(val statements: List<Statement> = emptyList()) :
    PrintWithIndentation {

  fun head() = statements.first()

  fun end() = statements.last()

  fun tail() = statements.slice(1 ..< statements.size)

  fun front() = statements.slice(0 ..< statements.size - 1)

  fun isExhausted() = statements.isEmpty()

  override fun toIndentedString(indent: String): String =
      statements.joinToString("", transform = { s -> s.toIndentedString(indent) })
}

fun concat(seq1: List<Statement>, seq2: List<Statement>) = SequenceOfStatements(seq1 + seq2)
