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

import tools.aqua.konstraints.smt.SatStatus
import tools.aqua.wvm.analysis.hoare.SMTSolver
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
    val apps = mutableListOf<StatementApp>()
    for (a in addr.evaluate(cfg.scope, cfg.memory, cfg.pathConstraint)) {
      for (e in expr.evaluate(cfg.scope, cfg.memory, cfg.pathConstraint)) {
        if (a is Error) {
          apps.addLast(
            NestedStatementError(
              "AssErr",
              a,
              this,
              Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, And(a.pc, e.pc))
              ),
              And(a.pc, e.pc)
            )
          )
          continue
        }
        if (e is Error) {
          apps.addLast(
            NestedStatementError(
              "AssErr",
              e,
              this,
              Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, And(a.pc, e.pc))
              ),
              And(a.pc, e.pc)
            )
          )
          continue
        }
        apps.addLast(
          AssOk(
            a as AddressOk,
            e as ArithmeticExpressionOk,
            this,
            Transition(
              cfg,
              dst =
              Configuration(
                SequenceOfStatements(cfg.statements.tail()),
                cfg.scope,
                cfg.memory.write(a.result, e.result), false, And(a.pc, e.pc)
              )
            ),
            And(a.pc, e.pc)
          )
        )
      }
    }
    return apps
  }

  override fun toIndentedString(indent: String) = "${indent}$addr := $expr;\n"
}

data class Swap(val left: AddressExpression, val right: AddressExpression) : Statement {

  override fun execute(cfg: Configuration,
                       input: Scanner?,
                       symbolic: Boolean): List<StatementApp> {
    val apps = mutableListOf<StatementApp>()
    for (a1 in left.evaluate(cfg.scope, cfg.memory, cfg.pathConstraint)) {
      for (a2 in right.evaluate(cfg.scope, cfg.memory, cfg.pathConstraint)) {
        if (a1 is Error) {
          apps.addLast(
            NestedStatementError(
              "SwapErr",
              a1,
              this,
              Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, And(a1.pc, a2.pc))
              ),
              And(a1.pc, a2.pc)
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
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, And(a1.pc, a2.pc))
              ),
              And(a1.pc, a2.pc)
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
                cfg.memory.write(a1.result, e2).write(a2.result, e1),
                false, And(a1.pc, a2.pc)
              )
            ),
            And(a1.pc, a2.pc)
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
    for (b in cond.evaluate(cfg.scope, cfg.memory, cfg.pathConstraint)) {
      if (b is Error) {
        apps.addLast(
          NestedStatementError(
            "AssertErr",
            b,
            this,
            Transition(
              cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, b.pc)
            ),
            b.pc
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
                  SequenceOfStatements(cfg.statements.tail()), cfg.scope, cfg.memory, false, And(b.pc, b.result)
                )
              ),
              b.pc
            )
          )

        False ->
          apps.addLast(
            AssertErr(
              b as BooleanExpressionOk,
              Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, error = true, And(b.pc, b.result))
              ),
              b.pc
            )
          )

        else -> {
          val smtSolver = SMTSolver()
          val constraint = And(cfg.pathConstraint, Not(b.result))
          val result = smtSolver.solve(constraint)
          if (result.status == SatStatus.UNSAT) {
            apps.addLast(
              AssertOK(
                b as BooleanExpressionOk,
                Transition(
                  cfg,
                  dst =
                  Configuration(
                    SequenceOfStatements(cfg.statements.tail()), cfg.scope, cfg.memory, false, b.pc
                  )
                ),
                b.pc
              )
            )
          } else {
            apps.addLast(
              AssertErr(
                b as BooleanExpressionOk,
                Transition(
                  cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, error = true, b.pc)
                ),
                b.pc
              )
            )

          }
        }
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
    for (b in cond.evaluate(cfg.scope, cfg.memory, cfg.pathConstraint)) {
      if (b is Error) {
        apps.addLast(
          NestedStatementError(
            "IfErr",
            b,
            this,
            Transition(
              cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, b.pc)
            ),
            b.pc
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
                  cfg.memory,
                  false,
                  b.pc
                )
              ),
              b.pc
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
                  cfg.memory,
                  false,
                  b.pc
                )
              ),
              b.pc
            )
          )

        else -> {
          val smtSolver1 = SMTSolver()
          val constraint1 = And(b.pc, b.result)
          val result1 = smtSolver1.solve(constraint1)
          if (result1.status == SatStatus.SAT) {
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
                    cfg.memory,
                    false,
                    And(b.pc, b.result)
                  )
                ),
                And(b.pc, b.result)
              )
            )
          }
          val smtSolver2 = SMTSolver()
          val constraint2 = And(b.pc, Not(b.result))
          val result2 = smtSolver2.solve(constraint2)
          if (result2.status == SatStatus.SAT) {
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
                    cfg.memory,
                    false,
                    And(b.pc, Not(b.result))
                  )
                ),
                And(b.pc, Not(b.result))
              )
            )

          }
        }
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
    for (cond in head.evaluate(cfg.scope, cfg.memory, cfg.pathConstraint)) {
      for (invar in invariant.evaluate(cfg.scope, cfg.memory, cfg.pathConstraint)) {
        if (cond is Error) {
          apps.addLast(
            NestedStatementError(
              "IfErr",
              cond,
              this,
              Transition(
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, And(cond.pc, invar.pc))
              ),
              And(cond.pc, invar.pc)
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
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, And(cond.pc, invar.pc))
              ),
              And(cond.pc, invar.pc)
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
                    concat(body.statements, cfg.statements.statements), cfg.scope, cfg.memory, false, And(cond.pc, invar.pc)
                  )
                ),
                And(cond.pc, invar.pc)
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
                    SequenceOfStatements(cfg.statements.tail()), cfg.scope, cfg.memory, false, And(cond.pc, invar.pc)
                  )
                ),
                And(cond.pc, invar.pc)
              )
            )

          (True to False) ->
            apps.addLast(
              WhInvar(
                cond as BooleanExpressionOk,
                invar as BooleanExpressionOk,
                this,
                Transition(
                  cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, And(cond.pc, invar.pc))
                ),
                And(cond.pc, invar.pc)
              )
            )

          (False to False) ->
            apps.addLast(
              WhInvar(
                cond as BooleanExpressionOk,
                invar as BooleanExpressionOk,
                this,
                Transition(
                  cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, And(cond.pc, invar.pc))
                ),
                And(cond.pc, invar.pc)
              )
            )

          else -> {
            val smtSolver1 = SMTSolver()
            val constraint1 = And(cfg.pathConstraint, cond.result)
            val result1 = smtSolver1.solve(constraint1)
            val smtSolver2 = SMTSolver()
            val constraint2 = And(cfg.pathConstraint, Not(invar.result))
            val result2 = smtSolver2.solve(constraint2)
            when((result1.status == SatStatus.SAT) to (result2.status == SatStatus.UNSAT)){
              true to true -> {
                apps.addLast(
                  WhTrue(
                    cond as BooleanExpressionOk,
                    invar as BooleanExpressionOk,
                    this,
                    Transition(
                      cfg,
                      dst =
                      Configuration(
                        concat(body.statements, cfg.statements.statements),
                        cfg.scope,
                        cfg.memory,
                        false,
                        And(cond.pc, invar.pc)
                      )
                    ),
                    And(cond.pc, invar.pc)
                  )
                )
              }
              false to true ->
                apps.addLast(
                  WhFalse(
                    cond as BooleanExpressionOk,
                    invar as BooleanExpressionOk,
                    this,
                    Transition(
                      cfg,
                      dst =
                      Configuration(
                        SequenceOfStatements(cfg.statements.tail()), cfg.scope, cfg.memory, false, And(cfg.pathConstraint, And(invar.pc, Not(cond.result)))
                      )
                    ),
                    And(cfg.pathConstraint, And(invar.pc, Not(cond.result)))
                  )
                )
              true to false ->
                apps.addLast(
                  WhInvar(
                    cond as BooleanExpressionOk,
                    invar as BooleanExpressionOk,
                    this,
                    Transition(
                      cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, And(cond.pc, Not(invar.result)))
                    ),
                    And(cond.pc, Not(invar.result))
                  )
                )
              false to false ->
                apps.addLast(
                  WhInvar(
                    cond as BooleanExpressionOk,
                    invar as BooleanExpressionOk,
                    this,
                    Transition(
                      cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, And(cfg.pathConstraint, And(Not(invar.result), Not(cond.result))))
                    ),
                    And(cfg.pathConstraint, And(Not(invar.result), Not(cond.result)))
                  )
                )
            }
            val smtSolver3 = SMTSolver()
            val constraint3 = And(cfg.pathConstraint, Not(cond.result))
            val result3 = smtSolver3.solve(constraint3)
            val smtSolver4 = SMTSolver()
            val constraint4 = And(cfg.pathConstraint, Not(invar.result))
            val result4 = smtSolver4.solve(constraint4)
            when((result3.status == SatStatus.SAT) to (result4.status == SatStatus.UNSAT)){
              true to true ->
                apps.addLast(
                  WhFalse(
                    cond as BooleanExpressionOk,
                    invar as BooleanExpressionOk,
                    this,
                    Transition(
                      cfg,
                      dst =
                      Configuration(
                        SequenceOfStatements(cfg.statements.tail()), cfg.scope, cfg.memory, false, And(cfg.pathConstraint, And(invar.pc,Not(cond.result)))
                      )
                    ),
                    And(cfg.pathConstraint, And(invar.pc,Not(cond.result)))
                  )
                )
              false to true ->{

              }
              true to false ->
                apps.addLast(
                  WhInvar(
                    cond as BooleanExpressionOk,
                    invar as BooleanExpressionOk,
                    this,
                    Transition(
                      cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, And(cfg.pathConstraint, And(Not(invar.result), Not(cond.result))))
                    ),
                    And(cfg.pathConstraint, And(Not(invar.result), Not(cond.result)))
                  )
                )
              false to false -> {

              }
            }
          }
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
      for (e in v.evaluate(cfg.scope, cfg.memory, cfg.pathConstraint)) {
        valuesL.last().addLast(e)
      }
    }
    val valueCombinations = valuesL.multiply()
    val apps = mutableListOf<StatementApp>()
    for (comb in valueCombinations) {
      val combPC = comb.map {it.pc}.reduce { acc, exp -> And(acc,exp) }
      if (comb.any { it is Error }) {
        apps.addLast(NestedStatementError(
          "PrintErr",
          comb.first { it is Error } as Error,
          this,
          Transition(
            cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, combPC)
          ),
          combPC
        ))
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
            Configuration(SequenceOfStatements(cfg.statements.tail()), cfg.scope, cfg.memory, false, combPC)
          ),
          combPC
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
    for (a in addr.evaluate(cfg.scope, cfg.memory, cfg.pathConstraint)) {
      if (a is Error) {
        apps.addLast(
          NestedStatementError(
            "HavocErr",
            a,
            this,
            Transition(
              cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, a.pc)
            ),
            a.pc
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
                cfg, dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, a.pc)
              ),
              a.pc
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
                  cfg.memory.write(a.result, NumericLiteral(number)),
                  false,
                  a.pc
                )
              ),
              a.pc
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
                cfg.memory.write(a.result, ValAtAddr(Variable(symbolName))),
                false,
                And(
                  a.pc,
                  And(
                    Lte(NumericLiteral(lower),ValAtAddr(Variable(symbolName))),
                    Gt(NumericLiteral(upper),ValAtAddr(Variable(symbolName)))))
              )
            ),
            And(
              a.pc,
              And(
                Gte(NumericLiteral(lower),ValAtAddr(Variable(symbolName))),
                Lte(NumericLiteral(upper),ValAtAddr(Variable(symbolName)))))
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
              dst = Configuration(SequenceOfStatements(), cfg.scope, cfg.memory, true, cfg.pathConstraint)),
        cfg.pathConstraint))

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
