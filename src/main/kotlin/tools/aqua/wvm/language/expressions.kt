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
import java.math.BigInteger
import tools.aqua.wvm.analysis.semantics.*
import tools.aqua.wvm.machine.Memory
import tools.aqua.wvm.machine.Scope
import tools.aqua.wvm.analysis.hoare.SMTSolver


sealed interface Expression<T> {
  fun evaluate(scope: Scope, memory: Memory<ArithmeticExpression>, pathConstraint: BooleanExpression = True): List<Application<T>>
}

sealed interface ArithmeticExpression : Expression<ArithmeticExpression>

sealed interface AddressExpression : Expression<Int>

sealed interface BooleanExpression : Expression<BooleanExpression>

// Address Expressions

data class Variable(val name: String) : AddressExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<Int>> =
      if (scope.defines(name)) listOf(VarOk(this, scope.resolve(name))) else listOf(VarErr(this))

  override fun toString(): String = "$name"
}

data class DeRef(val reference: AddressExpression) : AddressExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<Int>> {
    val apps = mutableListOf<Application<Int>>()
    for( refApp in reference.evaluate(scope, memory)) {
      if (refApp is Error) {
        apps.addLast(NestedAddressError("DeRefNestedError", refApp, this))
        continue
      }
      // the semantics guarantee that refApp.result is a valid address
      val tmp = memory.read(refApp.result)
      val address = when(tmp){
        is NumericLiteral -> listOf(tmp.literal)
        else -> {
          val smtSolver = SMTSolver()
          val constraint =
            And(pathConstraint,
              Eq(ValAtAddr(Variable("addr")), tmp, 0)
            )
          var result = smtSolver.solve(constraint)
          val addresses = mutableListOf<BigInteger>()
          while(result.status == SatStatus.SAT) {
            addresses.addLast(result.model["addr"]!!.toBigInteger())
            result = smtSolver.solve(constraint)
          }
          addresses
        }
      }
      val derefs =
        address.map {
          if (it in BigInteger.ZERO ..< memory.size().toBigInteger())
            DeRefOk(refApp as AddressOk, this, it.toInt())
          else DeRefAddressError(refApp as AddressOk, this, it.toInt())
      }
      apps.addAll(derefs)
    }
    return apps
  }

  override fun toString(): String = "*$reference"
}

data class ArrayAccess(val array: ValAtAddr, val index: ArithmeticExpression) : AddressExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<Int>> {
    val apps = mutableListOf<Application<Int>>()
    for (base in array.evaluate(scope, memory)) {
      for (offset in index.evaluate(scope, memory)) {
        if (base is ArithmeticExpressionError) {
          apps.addLast(NestedAddressError("ArrayAddressError", base, this))
          continue
        }
        if (offset is ArithmeticExpressionError) {
          apps.addLast(NestedAddressError("ArrayIndexError", offset, this))
          continue
        }
        val smtSolver = SMTSolver()
        val constraint =
          And(
            pathConstraint,
            And(
              Eq(ValAtAddr(Variable("base")), base.result, 0),
              Eq(ValAtAddr(Variable("offset")), offset.result, 0)
            )
          )
        var result = smtSolver.solve(constraint)
        while (result.status == SatStatus.SAT) {
          val b = result.model["base"]!!.toInt()
          val o = result.model["offset"]!!.toInt()
          val a = b + o
          if (a in 0 ..< memory.size())
            apps.addLast(ArrayAccessOk(base as ValAtAddrOk, offset as ArithmeticExpressionOk, this, a))
          else
            apps.addLast(ArrayAccessError(
              base as ValAtAddrOk, offset as ArithmeticExpressionOk, this, a))
          result = smtSolver.solve(constraint)
        }
      }
    }
    return apps
  }

  override fun toString(): String = "$array[$index]"
}

// Arithmetic Expressions

data class Add(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    ArithmeticExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<ArithmeticExpression>> {
    val apps = mutableListOf<Application<ArithmeticExpression>>()
    for (left in left.evaluate(scope, memory)) {
      for (right in right.evaluate(scope, memory)) {
        if (left is ArithmeticExpressionError) {
          apps.addLast(NestedArithmeticError("AddLeftErr", left, this))
          continue
        }
        if (right is ArithmeticExpressionError) {
          apps.addLast(NestedArithmeticError("AddRightErr", right, this))
          continue
        }
        val resultExp =
          if (left.result is NumericLiteral &&
              right.result is NumericLiteral) {
            NumericLiteral(left.result.literal.plus(right.result.literal))
          } else {
            Add(left.result, right.result)
          }
        apps.addLast(
          AddOk(
            left as ArithmeticExpressionOk,
            right as ArithmeticExpressionOk,
            this,
            resultExp
          )
        )
      }
    }
    return apps
  }

  override fun toString(): String = "($left + $right)"
}

data class Sub(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    ArithmeticExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<ArithmeticExpression>> {
    val apps = mutableListOf<Application<ArithmeticExpression>>()
    for (left in left.evaluate(scope, memory)) {
      for (right in right.evaluate(scope, memory)) {
        if (left is ArithmeticExpressionError) {
          apps.addLast(NestedArithmeticError("SubLeftErr", left, this))
          continue
        }
        if (right is ArithmeticExpressionError) {
          apps.addLast(NestedArithmeticError("SubRightErr", right, this))
          continue
        }
        val resultExp =
          if (left.result is NumericLiteral &&
            right.result is NumericLiteral) {
            NumericLiteral(left.result.literal.minus(right.result.literal))
          } else {
            Sub(left.result, right.result)
          }
        apps.addLast(
          SubOk(
            left as ArithmeticExpressionOk,
            right as ArithmeticExpressionOk,
            this,
            resultExp
          )
        )
      }
    }
    return apps
  }

  override fun toString(): String = "($left - $right)"
}

data class Mul(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    ArithmeticExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<ArithmeticExpression>> {
    val apps = mutableListOf<Application<ArithmeticExpression>>()
    for (left in left.evaluate(scope, memory)) {
      for (right in right.evaluate(scope, memory)) {
        if (left is ArithmeticExpressionError) {
          apps.addLast(NestedArithmeticError("MulLeftErr", left, this))
          continue
        }
        if (right is ArithmeticExpressionError) {
          apps.addLast(NestedArithmeticError("MulRightErr", right, this))
          continue
        }
        val resultExp =
          if (left.result is NumericLiteral &&
            right.result is NumericLiteral) {
            NumericLiteral(left.result.literal * right.result.literal)
          } else {
            Mul(left.result, right.result)
          }
        apps.addLast(
          MulOk(
            left as ArithmeticExpressionOk,
            right as ArithmeticExpressionOk,
            this,
            resultExp
          )
        )
      }
    }
    return apps
  }

  override fun toString(): String = "($left * $right)"
}

data class Div(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    ArithmeticExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<ArithmeticExpression>> {
    val apps = mutableListOf<Application<ArithmeticExpression>>()
    for (left in left.evaluate(scope, memory)) {
      for (right in right.evaluate(scope, memory)) {
        if (left is ArithmeticExpressionError) {
          apps.addLast(NestedArithmeticError("DivLeftErr", left, this))
          continue
        }
        if (right is ArithmeticExpressionError) {
          apps.addLast(NestedArithmeticError("DivRightErr", right, this))
          continue
        }
        val resultExp =
          if (left.result is NumericLiteral &&
            right.result is NumericLiteral) {
            NumericLiteral(left.result.literal.div(right.result.literal))
          } else {
            Div(left.result, right.result)
          }
        apps.addLast(
          DivOk(
            left as ArithmeticExpressionOk,
            right as ArithmeticExpressionOk,
            this,
            resultExp
          )
        )
      }
    }
    return apps
  }

  override fun toString(): String = "($left / $right)"
}

data class Rem(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    ArithmeticExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<ArithmeticExpression>> {
    val apps = mutableListOf<Application<ArithmeticExpression>>()
    for (left in left.evaluate(scope, memory)) {
      for (right in right.evaluate(scope, memory)) {
        if (left is ArithmeticExpressionError) {
          apps.addLast(NestedArithmeticError("RemLeftErr", left, this))
          continue
        }
        if (right is ArithmeticExpressionError) {
          apps.addLast(NestedArithmeticError("RemRightErr", right, this))
          continue
        }
        val resultExp =
          if (left.result is NumericLiteral &&
            right.result is NumericLiteral) {
            NumericLiteral(left.result.literal.rem(right.result.literal))
          } else {
            Rem(left.result, right.result)
          }
        apps.addLast(
          RemOk(
            left as ArithmeticExpressionOk,
            right as ArithmeticExpressionOk,
            this,
            resultExp
          )
        )
      }
    }
    return apps
  }

  override fun toString(): String = "($left % $right)"
}

data class UnaryMinus(val negated: ArithmeticExpression) : ArithmeticExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<ArithmeticExpression>> {
    val apps = mutableListOf<Application<ArithmeticExpression>>()
    for (inner in negated.evaluate(scope, memory)) {
        if (inner is ArithmeticExpressionError) {
          apps.addLast(NestedArithmeticError("UnaryMinusErr", inner, this))
          continue
        }
        val resultExp = if (inner.result is NumericLiteral) {
          NumericLiteral(inner.result.literal.unaryMinus())
        } else {
          UnaryMinus(inner.result)
        }
        apps.addLast(
          UnaryMinusOk(
            inner as ArithmeticExpressionOk,
            this,
            resultExp
          )
        )

    }
    return apps
  }

  override fun toString(): String = "-($negated)"
}

data class NumericLiteral(val literal: BigInteger) : ArithmeticExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<ArithmeticExpression>> =
      listOf(NumericLiteralOk(this))

  override fun toString(): String = "$literal"
}

data class ValAtAddr(val addr: AddressExpression) : ArithmeticExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<ArithmeticExpression>> {
    val apps = mutableListOf<Application<ArithmeticExpression>>()
    for (addrApp in addr.evaluate(scope, memory)) {
      if (addrApp is AddressError) apps.addLast(NestedArithmeticError("ValAtAddrErr", addrApp, this))
      else apps.addLast(ValAtAddrOk(addrApp as AddressOk, this, memory.read(addrApp.result)))
    }
    return apps
  }

  override fun toString(): String = addr.toString()
}

data class VarAddress(val variable: Variable) : ArithmeticExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<ArithmeticExpression>> {
    val apps = mutableListOf<Application<ArithmeticExpression>>()
    for (varAddress in variable.evaluate(scope, memory)) {
      if (varAddress is AddressError) apps.addLast(NestedArithmeticError("VarAddrErr", varAddress, this))
      else apps.addLast(VarAddrOk(varAddress as AddressOk, this, varAddress.result.toBigInteger()))
    }
    return apps
  }

  override fun toString(): String = "&$variable"
}

// Boolean Expressions

data class Eq(val left: ArithmeticExpression, val right: ArithmeticExpression, val nesting: Int) :
    BooleanExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<BooleanExpression>> {
    val apps = mutableListOf<Application<BooleanExpression>>()
    for (left in left.evaluate(scope, memory)) {
      for (right in right.evaluate(scope, memory)) {
        if (left is ArithmeticExpressionError) {
          apps.addLast(NestedBooleanError("EqLeftErr", left, this))
          continue
        }
      if (right is ArithmeticExpressionError) {
        apps.addLast(NestedBooleanError("EqRightErr", right, this))
        continue
      }
      val resultExp =
        if (left.result is NumericLiteral &&
            right.result is NumericLiteral) {
          if (left.result.literal == right.result.literal) True else False
        } else {
          Eq(left.result, right.result, nesting)
        }
      apps.addLast(EqOk(
        left as ArithmeticExpressionOk,
        right as ArithmeticExpressionOk,
        this,
        resultExp
      ))
      }
    }
    return apps
  }

  private fun opString(i: Int): String = if (i >= 0) "=${opString(i-1)}" else ""

  override fun toString(): String = "($left ${opString(nesting)} $right)"
}

data class Gt(val left: ArithmeticExpression, val right: ArithmeticExpression) : BooleanExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<BooleanExpression>> {
    val apps = mutableListOf<Application<BooleanExpression>>()
    for (left in left.evaluate(scope, memory)) {
      for (right in right.evaluate(scope, memory)) {
        if (left is ArithmeticExpressionError) {
          apps.addLast(NestedBooleanError("GtLeftErr", left, this))
          continue
        }
        if (right is ArithmeticExpressionError) {
          apps.addLast(NestedBooleanError("GtRightErr", right, this))
          continue
        }
        val resultExp =
          if (left.result is NumericLiteral &&
            right.result is NumericLiteral) {
            if (left.result.literal > right.result.literal) True else False
          } else {
            Gt(left.result, right.result)
          }
        apps.addLast(GtOk(
          left as ArithmeticExpressionOk,
          right as ArithmeticExpressionOk,
          this,
          resultExp
        ))
      }
    }
    return apps
  }

  override fun toString(): String = "($left > $right)"
}

data class Gte(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    BooleanExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<BooleanExpression>> {
    val apps = mutableListOf<Application<BooleanExpression>>()
    for (left in left.evaluate(scope, memory)) {
      for (right in right.evaluate(scope, memory)) {
        if (left is ArithmeticExpressionError) {
          apps.addLast(NestedBooleanError("GteLeftErr", left, this))
          continue
        }
        if (right is ArithmeticExpressionError) {
          apps.addLast(NestedBooleanError("GteRightErr", right, this))
          continue
        }
        val resultExp =
          if (left.result is NumericLiteral &&
            right.result is NumericLiteral) {
            if (left.result.literal >= right.result.literal) True else False
          } else {
            Gte(left.result, right.result)
          }
        apps.addLast(GteOk(
          left as ArithmeticExpressionOk,
          right as ArithmeticExpressionOk,
          this,
          resultExp
        ))
      }
    }
    return apps
  }

  override fun toString(): String = "($left >= $right)"
}

data class Lt(val left: ArithmeticExpression, val right: ArithmeticExpression) : BooleanExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<BooleanExpression>> {
    val apps = mutableListOf<Application<BooleanExpression>>()
    for (left in left.evaluate(scope, memory)) {
      for (right in right.evaluate(scope, memory)) {
        if (left is ArithmeticExpressionError) {
          apps.addLast(NestedBooleanError("LtLeftErr", left, this))
          continue
        }
        if (right is ArithmeticExpressionError) {
          apps.addLast(NestedBooleanError("LtRightErr", right, this))
          continue
        }
        val resultExp =
          if (left.result is NumericLiteral &&
            right.result is NumericLiteral) {
            if (left.result.literal < right.result.literal) True else False
          } else {
            Lt(left.result, right.result)
          }
        apps.addLast(LtOk(
          left as ArithmeticExpressionOk,
          right as ArithmeticExpressionOk,
          this,
          resultExp
        ))
      }
    }
    return apps
  }

  override fun toString(): String = "($left < $right)"
}

data class Lte(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    BooleanExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<BooleanExpression>> {
    val apps = mutableListOf<Application<BooleanExpression>>()
    for (left in left.evaluate(scope, memory)) {
      for (right in right.evaluate(scope, memory)) {
        if (left is ArithmeticExpressionError) {
          apps.addLast(NestedBooleanError("LteLeftErr", left, this))
          continue
        }
        if (right is ArithmeticExpressionError) {
          apps.addLast(NestedBooleanError("LteRightErr", right, this))
          continue
        }
        val resultExp =
          if (left.result is NumericLiteral &&
            right.result is NumericLiteral) {
            if (left.result.literal <= right.result.literal) True else False
          } else {
            Lte(left.result, right.result)
          }
        apps.addLast(LteOk(
          left as ArithmeticExpressionOk,
          right as ArithmeticExpressionOk,
          this,
          resultExp
        ))
      }
    }
    return apps
  }

  override fun toString(): String = "($left <= $right)"
}

data class And(val left: BooleanExpression, val right: BooleanExpression) : BooleanExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<BooleanExpression>> {
    val apps = mutableListOf<Application<BooleanExpression>>()
    for (left in left.evaluate(scope, memory)){
      for (right in right.evaluate(scope, memory)) {
        if (left is BooleanExpressionError) {
          apps.addLast(NestedBooleanError("AndLeftErr", left, this))
          continue
        }
        if (right is BooleanExpressionError) {
          apps.addLast(NestedBooleanError("AndRightErr", right, this))
          continue
        }
        val resultExp = if (left.result is True && right.result is True) {
          True
        } else if (left.result is True && right.result is False) {
          False
        } else if (left.result is False && right.result is True) {
          False
        } else if (left.result is False && right.result is False) {
          False
        } else {
          And(left.result, right.result)
        }
        apps.addLast(AndOk(
          left as BooleanExpressionOk,
          right as BooleanExpressionOk,
          this,
          resultExp))
      }
    }
    return apps
  }

  override fun toString(): String = "($left and $right)"
}

data class Or(val left: BooleanExpression, val right: BooleanExpression) : BooleanExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<BooleanExpression>> {
    val apps = mutableListOf<Application<BooleanExpression>>()
    for (left in left.evaluate(scope, memory)){
      for (right in right.evaluate(scope, memory)) {
        if (left is BooleanExpressionError) {
          apps.addLast(NestedBooleanError("OrLeftErr", left, this))
          continue
        }
        if (right is BooleanExpressionError) {
          apps.addLast(NestedBooleanError("OrRightErr", right, this))
          continue
        }
        val resultExp = if (left.result is True && right.result is True) {
          True
        } else if (left.result is True && right.result is False) {
          True
        } else if (left.result is False && right.result is True) {
          True
        } else if (left.result is False && right.result is False) {
          False
        } else {
          Or(left.result, right.result)
        }
        apps.addLast(OrOk(
          left as BooleanExpressionOk,
          right as BooleanExpressionOk,
          this,
          resultExp))
      }
    }
    return apps
  }

  override fun toString(): String = "($left or $right)"
}

data class Imply(val left: BooleanExpression, val right: BooleanExpression) : BooleanExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<BooleanExpression>> {
    val apps = mutableListOf<Application<BooleanExpression>>()
    for (left in left.evaluate(scope, memory)){
      for (right in right.evaluate(scope, memory)) {
        if (left is BooleanExpressionError) {
          apps.addLast(NestedBooleanError("ImplyLeftErr", left, this))
          continue
        }
        if (right is BooleanExpressionError) {
          apps.addLast(NestedBooleanError("ImplyRightErr", right, this))
          continue
        }
        val resultExp = if (left.result is True && right.result is True) {
          True
        } else if (left.result is True && right.result is False) {
          False
        } else if (left.result is False && right.result is True) {
          True
        } else if (left.result is False && right.result is False) {
          True
        } else {
          Imply(left.result, right.result)
        }
        apps.addLast(ImplyOk(
          left as BooleanExpressionOk,
          right as BooleanExpressionOk,
          this,
          resultExp))
      }
    }
    return apps
  }

  override fun toString(): String = "($left => $right)"
}

data class Equiv(val left: BooleanExpression, val right: BooleanExpression) : BooleanExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<BooleanExpression>> {
    val apps = mutableListOf<Application<BooleanExpression>>()
    for (left in left.evaluate(scope, memory)){
      for (right in right.evaluate(scope, memory)) {
        if (left is BooleanExpressionError) {
          apps.addLast(NestedBooleanError("EquivLeftErr", left, this))
          continue
        }
        if (right is BooleanExpressionError) {
          apps.addLast(NestedBooleanError("EquivRightErr", right, this))
          continue
        }
        val resultExp = if (left.result is True && right.result is True) {
          True
        } else if (left.result is True && right.result is False) {
          False
        } else if (left.result is False && right.result is True) {
          False
        } else if (left.result is False && right.result is False) {
          True
        } else {
          Equiv(left.result, right.result)
        }
        apps.addLast(EquivOk(
          left as BooleanExpressionOk,
          right as BooleanExpressionOk,
          this,
          resultExp))
      }
    }
    return apps
  }

  override fun toString(): String = "($left <=> $right)"
}

data class Not(val negated: BooleanExpression) : BooleanExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<BooleanExpression>> {
    val apps = mutableListOf<Application<BooleanExpression>>()
    for (inner in negated.evaluate(scope, memory)) {
      if (inner is BooleanExpressionError) {
        apps.addLast(NestedBooleanError("NotErr", inner, this))
        continue
      }
      val resultExp =
        if (inner.result is True) {
          False
        } else if (inner.result is False) {
          True
        } else {
          Not(inner.result)
        }
      apps.addLast(NotOk(inner as BooleanExpressionOk, this, resultExp))
    }
    return apps
  }

  override fun toString(): String = "(not $negated)"
}

object True : BooleanExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<BooleanExpression>> = listOf(TrueOk(this))

  override fun toString(): String = "true"
}

object False : BooleanExpression {
  override fun evaluate(
    scope: Scope,
    memory: Memory<ArithmeticExpression>,
    pathConstraint: BooleanExpression
  ): List<Application<BooleanExpression>> = listOf(FalseOk(this))

  override fun toString(): String = "false"
}

fun toExpression(b : Boolean) : BooleanExpression =
  if (b) True else False

// --------------------------------------------------------------------
// Verification Expressions

data class Forall(val boundVar: Variable, val expression: BooleanExpression) : BooleanExpression {
    override fun evaluate(
      scope: Scope,
      memory: Memory<ArithmeticExpression>,
      pathConstraint: BooleanExpression
    ): List<Application<BooleanExpression>> {
        throw Exception("forall is not meant to be evaluated.")
    }

    override fun toString(): String = "∀$boundVar. ($expression)"
}

sealed interface ArrayExpression : AddressExpression

object AnyArray : ArrayExpression {
    override fun evaluate(
      scope: Scope,
      memory: Memory<ArithmeticExpression>,
      pathConstraint: BooleanExpression
    ): List<Application<Int>> {
        throw Exception("array is not meant to be evaluated.")
    }

    override fun toString(): String = "M"
}

data class ArrayRead(val array:ArrayExpression, val index:ArithmeticExpression) : ArrayExpression {
    override fun evaluate(
      scope: Scope,
      memory: Memory<ArithmeticExpression>,
      pathConstraint: BooleanExpression
    ): List<Application<Int>> {
        throw Exception("array read is not meant to be evaluated.")
    }

    override fun toString(): String = "$array[$index]"
}

data class ArrayWrite(val array:ArrayExpression, val index:ArithmeticExpression, val value:ArithmeticExpression) : ArrayExpression {
    override fun evaluate(
      scope: Scope,
      memory: Memory<ArithmeticExpression>,
      pathConstraint: BooleanExpression
    ): List<Application<Int>> {
        throw Exception("array write is not meant to be evaluated.")
    }

    override fun toString(): String = "$array<$index <| $value>"
}