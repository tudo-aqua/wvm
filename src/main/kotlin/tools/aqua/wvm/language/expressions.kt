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
import tools.aqua.wvm.analysis.semantics.*
import tools.aqua.wvm.machine.Memory
import tools.aqua.wvm.machine.Scope

sealed interface Expression<T> {
  fun evaluate(scope: Scope, memory: Memory<BigInteger>): Application<T>
}

sealed interface ArithmeticExpression : Expression<BigInteger>

sealed interface AddressExpression : Expression<Int>

sealed interface BooleanExpression : Expression<Boolean>

// Address Expressions

data class Variable(val name: String) : AddressExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): AddressApp =
      if (scope.defines(name)) VarOk(this, scope.resolve(name)) else VarErr(this)

  override fun toString(): String = "$name"
}

data class DeRef(val reference: AddressExpression) : AddressExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): AddressApp {
    val refApp = reference.evaluate(scope, memory)
    if (refApp is Error) return NestedAddressError("DeRefNestedError", refApp, this)
    // the semantics guarantee that refApp.result is a valid address
    val address = memory.read(refApp.result)
    return if (address in BigInteger.ZERO ..< memory.size().toBigInteger())
        DeRefOk(refApp as AddressOk, this, address.toInt())
    else DeRefAddressError(refApp as AddressOk, this, address.toInt())
  }

  override fun toString(): String = "*$reference"
}

data class ArrayAccess(val array: ValAtAddr, val index: ArithmeticExpression) : AddressExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): AddressApp {
    val base = array.evaluate(scope, memory)
    val offset = index.evaluate(scope, memory)
    if (base is ArithmeticExpressionError)
        return NestedAddressError("ArrayAddressError", base, this)
    if (offset is ArithmeticExpressionError)
        return NestedAddressError("ArrayIndexError", offset, this)
    val address = base.result.plus(offset.result)
    return if (address in BigInteger.ZERO ..< memory.size().toBigInteger())
        ArrayAccessOk(base as ValAtAddrOk, offset as ArithmeticExpressionOk, this, address.toInt())
    else
        ArrayAccessError(
            base as ValAtAddrOk, offset as ArithmeticExpressionOk, this, address.toInt())
  }

  override fun toString(): String = "$array[$index]"
}

// Arithmetic Expressions

data class Add(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    ArithmeticExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): ArithmeticExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is ArithmeticExpressionError) return NestedArithmeticError("AddLeftErr", left, this)
    if (right is ArithmeticExpressionError) return NestedArithmeticError("AddRightErr", right, this)
    return AddOk(
        left as ArithmeticExpressionOk,
        right as ArithmeticExpressionOk,
        this,
        left.result.plus(right.result))
  }

  override fun toString(): String = "($left + $right)"
}

data class Sub(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    ArithmeticExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): ArithmeticExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is ArithmeticExpressionError) return NestedArithmeticError("SubLeftErr", left, this)
    if (right is ArithmeticExpressionError) return NestedArithmeticError("SubRightErr", right, this)
    return SubOk(
        left as ArithmeticExpressionOk,
        right as ArithmeticExpressionOk,
        this,
        left.result.minus(right.result))
  }

  override fun toString(): String = "($left - $right)"
}

data class Mul(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    ArithmeticExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): ArithmeticExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is ArithmeticExpressionError) return NestedArithmeticError("MulLeftErr", left, this)
    if (right is ArithmeticExpressionError) return NestedArithmeticError("MulRightErr", right, this)
    return MulOk(
        left as ArithmeticExpressionOk,
        right as ArithmeticExpressionOk,
        this,
        left.result * right.result)
  }

  override fun toString(): String = "($left * $right)"
}

data class Div(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    ArithmeticExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): ArithmeticExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is ArithmeticExpressionError) return NestedArithmeticError("DivLeftErr", left, this)
    if (right is ArithmeticExpressionError) return NestedArithmeticError("DivRightErr", right, this)
    if (right.result == BigInteger.ZERO) return DivZeroErr(right as ArithmeticExpressionOk, this)
    return DivOk(
        left as ArithmeticExpressionOk,
        right as ArithmeticExpressionOk,
        this,
        left.result.div(right.result))
  }

  override fun toString(): String = "($left / $right)"
}

data class Rem(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    ArithmeticExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): ArithmeticExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is ArithmeticExpressionError) return NestedArithmeticError("RemLeftErr", left, this)
    if (right is ArithmeticExpressionError) return NestedArithmeticError("RemRightErr", right, this)
    if (right.result == BigInteger.ZERO) return RemZeroErr(right as ArithmeticExpressionOk, this)
    return RemOk(
        left as ArithmeticExpressionOk,
        right as ArithmeticExpressionOk,
        this,
        left.result.rem(right.result))
  }

  override fun toString(): String = "($left % $right)"
}

data class UnaryMinus(val negated: ArithmeticExpression) : ArithmeticExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): ArithmeticExpressionApp {
    val inner = negated.evaluate(scope, memory)
    if (inner is ArithmeticExpressionError)
        return NestedArithmeticError("UnaryMinusErr", inner, this)
    return UnaryMinusOk(inner as ArithmeticExpressionOk, this, inner.result.unaryMinus())
  }

  override fun toString(): String = "-($negated)"
}

data class NumericLiteral(val literal: BigInteger) : ArithmeticExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): ArithmeticExpressionApp =
      NumericLiteralOk(this)

  override fun toString(): String = "$literal"
}

data class ValAtAddr(val addr: AddressExpression) : ArithmeticExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): ArithmeticExpressionApp {
    val addrApp = addr.evaluate(scope, memory)
    if (addrApp is AddressError) return NestedArithmeticError("ValAtAddrErr", addrApp, this)
    return ValAtAddrOk(addrApp as AddressOk, this, memory.read(addrApp.result))
  }

  override fun toString(): String = addr.toString()
}

data class VarAddress(val variable: Variable) : ArithmeticExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): ArithmeticExpressionApp {
    val varAddress = variable.evaluate(scope, memory)
    if (varAddress is AddressError) return NestedArithmeticError("VarAddrErr", varAddress, this)
    return VarAddrOk(varAddress as AddressOk, this, varAddress.result.toBigInteger())
  }

  override fun toString(): String = "&$variable"
}

// Boolean Expressions

data class Eq(val left: ArithmeticExpression, val right: ArithmeticExpression, val nesting: Int) :
    BooleanExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): BooleanExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is ArithmeticExpressionError) return NestedBooleanError("EqLeftErr", left, this)
    if (right is ArithmeticExpressionError) return NestedBooleanError("EqRightErr", right, this)
    return EqOk(
        left as ArithmeticExpressionOk,
        right as ArithmeticExpressionOk,
        this,
        left.result == right.result)
  }

  private fun opString(i: Int): String = if (i >= 0) "=${opString(i-1)}" else ""

  override fun toString(): String = "($left ${opString(nesting)} $right)"
}

data class Gt(val left: ArithmeticExpression, val right: ArithmeticExpression) : BooleanExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): BooleanExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is ArithmeticExpressionError) return NestedBooleanError("GtLeftErr", left, this)
    if (right is ArithmeticExpressionError) return NestedBooleanError("GtRightErr", right, this)
    return GtOk(
        left as ArithmeticExpressionOk,
        right as ArithmeticExpressionOk,
        this,
        left.result > right.result)
  }

  override fun toString(): String = "($left > $right)"
}

data class Gte(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    BooleanExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): BooleanExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is ArithmeticExpressionError) return NestedBooleanError("GteLeftErr", left, this)
    if (right is ArithmeticExpressionError) return NestedBooleanError("GteRightErr", right, this)
    return GteOk(
        left as ArithmeticExpressionOk,
        right as ArithmeticExpressionOk,
        this,
        left.result >= right.result)
  }

  override fun toString(): String = "($left >= $right)"
}

data class Lt(val left: ArithmeticExpression, val right: ArithmeticExpression) : BooleanExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): BooleanExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is ArithmeticExpressionError) return NestedBooleanError("LtLeftErr", left, this)
    if (right is ArithmeticExpressionError) return NestedBooleanError("LtRightErr", right, this)
    return LtOk(
        left as ArithmeticExpressionOk,
        right as ArithmeticExpressionOk,
        this,
        left.result < right.result)
  }

  override fun toString(): String = "($left < $right)"
}

data class Lte(val left: ArithmeticExpression, val right: ArithmeticExpression) :
    BooleanExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): BooleanExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is ArithmeticExpressionError) return NestedBooleanError("LteLeftErr", left, this)
    if (right is ArithmeticExpressionError) return NestedBooleanError("LteRightErr", right, this)
    return LteOk(
        left as ArithmeticExpressionOk,
        right as ArithmeticExpressionOk,
        this,
        left.result <= right.result)
  }

  override fun toString(): String = "($left <= $right)"
}

data class And(val left: BooleanExpression, val right: BooleanExpression) : BooleanExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): BooleanExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is BooleanExpressionError) return NestedBooleanError("AndLeftErr", left, this)
    if (right is BooleanExpressionError) return NestedBooleanError("AndRightErr", right, this)
    return AndOk(
        left as BooleanExpressionOk,
        right as BooleanExpressionOk,
        this,
        left.result && right.result)
  }

  override fun toString(): String = "($left and $right)"
}

data class Or(val left: BooleanExpression, val right: BooleanExpression) : BooleanExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): BooleanExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is BooleanExpressionError) return NestedBooleanError("OrLeftErr", left, this)
    if (right is BooleanExpressionError) return NestedBooleanError("OrRightErr", right, this)
    return OrOk(
        left as BooleanExpressionOk,
        right as BooleanExpressionOk,
        this,
        left.result || right.result)
  }

  override fun toString(): String = "($left or $right)"
}

data class Imply(val left: BooleanExpression, val right: BooleanExpression) : BooleanExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): BooleanExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is BooleanExpressionError) return NestedBooleanError("ImplyLeftErr", left, this)
    if (right is BooleanExpressionError) return NestedBooleanError("ImplyRightErr", right, this)
    return ImplyOk(
        left as BooleanExpressionOk,
        right as BooleanExpressionOk,
        this,
        !left.result || right.result)
  }

  override fun toString(): String = "($left => $right)"
}

data class Equiv(val left: BooleanExpression, val right: BooleanExpression) : BooleanExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): BooleanExpressionApp {
    val left = left.evaluate(scope, memory)
    val right = right.evaluate(scope, memory)
    if (left is BooleanExpressionError) return NestedBooleanError("EquivLeftErr", left, this)
    if (right is BooleanExpressionError) return NestedBooleanError("EquivRightErr", right, this)
    return EquivOk(
        left as BooleanExpressionOk,
        right as BooleanExpressionOk,
        this,
        left.result == right.result)
  }

  override fun toString(): String = "($left <=> $right)"
}

data class Not(val negated: BooleanExpression) : BooleanExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): BooleanExpressionApp {
    val inner = negated.evaluate(scope, memory)
    if (inner is BooleanExpressionError) return NestedBooleanError("NotErr", inner, this)
    return NotOk(inner as BooleanExpressionOk, this, !inner.result)
  }

  override fun toString(): String = "(not $negated)"
}

object True : BooleanExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): BooleanExpressionApp = TrueOk(this)

  override fun toString(): String = "true"
}

object False : BooleanExpression {
  override fun evaluate(scope: Scope, memory: Memory<BigInteger>): BooleanExpressionApp = FalseOk(this)

  override fun toString(): String = "false"
}

// --------------------------------------------------------------------
// Verification Expressions

data class Forall(val boundVar: Variable, val expression: BooleanExpression) : BooleanExpression {
    override fun evaluate(scope: Scope, memory: Memory<BigInteger>): Application<Boolean> {
        throw Exception("forall is not meant to be evaluated.")
    }

    override fun toString(): String = "∀$boundVar. ($expression)"
}

sealed interface ArrayExpression : AddressExpression

object AnyArray : ArrayExpression {
    override fun evaluate(scope: Scope, memory: Memory<BigInteger>): Application<Int> {
        throw Exception("array is not meant to be evaluated.")
    }

    override fun toString(): String = "M"
}

data class ArrayRead(val array:ArrayExpression, val index:ArithmeticExpression) : ArrayExpression {
    override fun evaluate(scope: Scope, memory: Memory<BigInteger>): Application<Int> {
        throw Exception("array read is not meant to be evaluated.")
    }

    override fun toString(): String = "$array[$index]"
}

data class ArrayWrite(val array:ArrayExpression, val index:ArithmeticExpression, val value:ArithmeticExpression) : ArrayExpression {
    override fun evaluate(scope: Scope, memory: Memory<BigInteger>): Application<Int> {
        throw Exception("array write is not meant to be evaluated.")
    }

    override fun toString(): String = "$array<$index <| $value>"
}