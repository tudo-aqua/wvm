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

package tools.aqua.wvm.analysis.semantics

import java.math.BigInteger
import tools.aqua.wvm.language.*

sealed class Application<T>(
    val result: T,
    val pc: BooleanExpression)

sealed interface Error {
  fun getError(): String
}

// --------------------------------------------------------------------

sealed class AddressApp(result: Int, pc: BooleanExpression) : Application<Int>(result, pc)

sealed class AddressOk(result: Int, pc: BooleanExpression) : AddressApp(result, pc)

sealed class AddressError(addr: Int, private val error: String, pc: BooleanExpression) : AddressApp(addr,pc), Error {
  override fun getError() = error
}

class NestedAddressError(val ruleName: String, val nested: Error, val expr: AddressExpression, pc: BooleanExpression) :
    AddressError(-1, "$ruleName: $expr.", pc)

class VarOk(val v: Variable, addr: Int, pc: BooleanExpression) : AddressOk(addr, pc)

class VarErr(val v: Variable, pc: BooleanExpression) : AddressError(-1, "Variable ${v.name} undefined.", pc)

class DeRefOk(val refOk: AddressOk, val deRef: DeRef, readValue: Int, pc: BooleanExpression) : AddressOk(readValue, pc)

class DeRefAddressError(val refOk: AddressOk, val deRef: DeRef, refValue: Int, pc: BooleanExpression) :
    AddressError(refValue, "Invalid address $refValue.", pc)

class ArrayAccessOk(
    val addrOk: ValAtAddrOk,
    val indexOk: ArithmeticExpressionOk,
    val arrayAccess: ArrayAccess,
    addrValue: Int,
    pc: BooleanExpression
) : AddressOk(addrValue, pc)

class ArrayAccessError(
    val arrayOk: ValAtAddrOk,
    val indexOk: ArithmeticExpressionOk,
    val arrayAccess: ArrayAccess,
    address: Int,
    pc: BooleanExpression
) : AddressError(address, "Invalid address $address.", pc)

// --------------------------------------------------------------------

sealed class ArithmeticExpressionApp(result: ArithmeticExpression, pc: BooleanExpression) : Application<ArithmeticExpression>(result, pc)

sealed class ArithmeticExpressionOk(result: ArithmeticExpression,pc: BooleanExpression) : ArithmeticExpressionApp(result,pc)

sealed class ArithmeticExpressionError(private val error: String, pc: BooleanExpression) :
    ArithmeticExpressionApp(NumericLiteral(BigInteger.ZERO), pc), Error {
  override fun getError() = error
}

class NestedArithmeticError(
    val ruleName: String,
    val nested: Error,
    val expr: ArithmeticExpression,
    pc: BooleanExpression
) : ArithmeticExpressionError("$ruleName: $expr.", pc)

class AddOk(
    val leftOk: ArithmeticExpressionOk,
    val rightOk: ArithmeticExpressionOk,
    val add: Add,
    sum: ArithmeticExpression,
    pc: BooleanExpression
) : ArithmeticExpressionOk(sum, pc)

class SubOk(
    val leftOk: ArithmeticExpressionOk,
    val rightOk: ArithmeticExpressionOk,
    val sub: Sub,
    diff: ArithmeticExpression,
    pc: BooleanExpression
) : ArithmeticExpressionOk(diff, pc)

class MulOk(
    val leftOk: ArithmeticExpressionOk,
    val rightOk: ArithmeticExpressionOk,
    val mul: Mul,
    product: ArithmeticExpression,
    pc: BooleanExpression
) : ArithmeticExpressionOk(product, pc)

class DivOk(
    val leftOk: ArithmeticExpressionOk,
    val rightOk: ArithmeticExpressionOk,
    val div: Div,
    quotient: ArithmeticExpression,
    pc: BooleanExpression
) : ArithmeticExpressionOk(quotient, pc)

class DivZeroErr(val rightOk: ArithmeticExpressionOk, val div: Div, pc: BooleanExpression) :
    ArithmeticExpressionError("Division By Zero in $div", pc)

class RemOk(
    val leftOk: ArithmeticExpressionOk,
    val rightOk: ArithmeticExpressionOk,
    val rem: Rem,
    remainder: ArithmeticExpression,
    pc: BooleanExpression
) : ArithmeticExpressionOk(remainder, pc)

class RemZeroErr(val rightOk: ArithmeticExpressionOk, val rem: Rem, pc: BooleanExpression) :
    ArithmeticExpressionError("Division By Zero in $rem", pc)

class UnaryMinusOk(
    val negated: ArithmeticExpressionOk,
    val unaryMinus: UnaryMinus,
    result: ArithmeticExpression,
    pc: BooleanExpression
) : ArithmeticExpressionOk(result, pc)

class ValAtAddrOk(val addrOk: AddressOk, val valAtAddr: ValAtAddr, value: ArithmeticExpression, pc: BooleanExpression) :
    ArithmeticExpressionOk(value, pc)

class VarAddrOk(val addrOk: AddressOk, val varAddress: VarAddress, value: BigInteger, pc: BooleanExpression) :
    ArithmeticExpressionOk(NumericLiteral(value), pc)

class NumericLiteralOk(val n: NumericLiteral, pc: BooleanExpression) : ArithmeticExpressionOk(n, pc)

// --------------------------------------------------------------------

sealed class BooleanExpressionApp(result: BooleanExpression, pc: BooleanExpression) : Application<BooleanExpression>(result, pc)

sealed class BooleanExpressionOk(result: BooleanExpression, pc: BooleanExpression) : BooleanExpressionApp(result, pc)

sealed class BooleanExpressionError(private val error: String, pc: BooleanExpression) :
    BooleanExpressionApp(False, pc), Error {
  override fun getError() = error
}

class NestedBooleanError(val ruleName: String, val nested: Error, val expr: Expression<*>, pc: BooleanExpression) :
    BooleanExpressionError("$ruleName: $expr.", pc)

class EqOk(
    val leftOk: ArithmeticExpressionOk,
    val rightOk: ArithmeticExpressionOk,
    val eq: Eq,
    result: BooleanExpression,
    pc: BooleanExpression
) : BooleanExpressionOk(result, pc)

class GtOk(
    val leftOk: ArithmeticExpressionOk,
    val rightOk: ArithmeticExpressionOk,
    val gt: Gt,
    result: BooleanExpression,
    pc: BooleanExpression
) : BooleanExpressionOk(result,pc)

class GteOk(
    val leftOk: ArithmeticExpressionOk,
    val rightOk: ArithmeticExpressionOk,
    val gte: Gte,
    result: BooleanExpression,
    pc: BooleanExpression
) : BooleanExpressionOk(result,pc)

class LtOk(
    val leftOk: ArithmeticExpressionOk,
    val rightOk: ArithmeticExpressionOk,
    val lt: Lt,
    result: BooleanExpression,
    pc: BooleanExpression
) : BooleanExpressionOk(result,pc)

class LteOk(
    val leftOk: ArithmeticExpressionOk,
    val rightOk: ArithmeticExpressionOk,
    val lte: Lte,
    result: BooleanExpression,
    pc: BooleanExpression
) : BooleanExpressionOk(result,pc)

class AndOk(
    val leftOk: BooleanExpressionOk,
    val rightOk: BooleanExpressionOk,
    val and: And,
    result: BooleanExpression,
    pc: BooleanExpression
) : BooleanExpressionOk(result,pc)

class OrOk(
    val leftOk: BooleanExpressionOk,
    val rightOk: BooleanExpressionOk,
    val or: Or,
    result: BooleanExpression,
    pc: BooleanExpression
) : BooleanExpressionOk(result,pc)

class ImplyOk(
    val leftOk: BooleanExpressionOk,
    val rightOk: BooleanExpressionOk,
    val imply: Imply,
    result: BooleanExpression,
    pc: BooleanExpression
) : BooleanExpressionOk(result,pc)

class EquivOk(
    val leftOk: BooleanExpressionOk,
    val rightOk: BooleanExpressionOk,
    val equiv: Equiv,
    result: BooleanExpression,
    pc: BooleanExpression
) : BooleanExpressionOk(result,pc)

class NotOk(val negated: BooleanExpressionOk, val not: Not, result: BooleanExpression, pc: BooleanExpression) :
    BooleanExpressionOk(result, pc)

class TrueOk(val tru: True,pc: BooleanExpression) : BooleanExpressionOk(True, pc)

class FalseOk(val fls: False,pc: BooleanExpression) : BooleanExpressionOk(False,pc)

// --------------------------------------------------------------------

sealed class StatementApp(result: Transition,pc: BooleanExpression) : Application<Transition>(result,pc)

sealed class StatementOk(result: Transition,pc: BooleanExpression) : StatementApp(result,pc)

sealed class StatementError(private val error: String, result: Transition, pc: BooleanExpression) :
    StatementApp(result,pc), Error {
  override fun getError() = error
}

class NestedStatementError(
    val ruleName: String,
    val nested: Error,
    val stmt: Statement,
    result: Transition,
    pc: BooleanExpression
) : StatementError("$ruleName: $stmt.", result,pc)

class AssOk(
    val addr: AddressOk,
    val expr: ArithmeticExpressionOk,
    val assign: Assignment,
    trans: Transition,
    pc: BooleanExpression
) : StatementOk(trans,pc)

class SwapOk(val a1: AddressOk, val a2: AddressOk, val swap: Swap, trans: Transition, pc: BooleanExpression) :
    StatementOk(trans,pc)

class AssertOK(val stmt: Assertion, val expr: BooleanExpressionOk, trans: Transition,pc: BooleanExpression) : StatementOk(trans,pc)

class AssertErr(val stmt: Assertion, val expr: BooleanExpressionOk, trans: Transition,pc: BooleanExpression) : StatementOk(trans, pc)

class IfTrue(val b: BooleanExpressionOk, val ifThenElse: IfThenElse, trans: Transition, pc: BooleanExpression) :
    StatementOk(trans,pc)

class IfFalse(val b: BooleanExpressionOk, val ifThenElse: IfThenElse, trans: Transition, pc: BooleanExpression) :
    StatementOk(trans,pc)

class WhInvar(
    val cond: BooleanExpressionOk,
    val invar: BooleanExpressionOk,
    val wh: While,
    trans: Transition,
    pc: BooleanExpression
) : StatementOk(trans,pc)

class WhTrue(
    val cond: BooleanExpressionOk,
    val invar: BooleanExpressionOk,
    val wh: While,
    trans: Transition,
    pc: BooleanExpression
) : StatementOk(trans, pc)

class WhFalse(
    val cond: BooleanExpressionOk,
    val invar: BooleanExpressionOk,
    val wh: While,
    trans: Transition,
    pc: BooleanExpression
) : StatementOk(trans, pc)

class PrintOk(val expr: List<ArithmeticExpressionOk>, val print: Print, trans: Transition, pc: BooleanExpression) :
    StatementOk(trans, pc)

class HavocOk(val addr: AddressOk, val havoc: Havoc, trans: Transition, pc: BooleanExpression) : StatementOk(trans, pc)

class HavocRangeErr(val addr: AddressOk, val havoc: Havoc, trans: Transition, pc: BooleanExpression) : StatementOk(trans,pc)

class FailOk(val fail: Fail, trans: Transition,pc: BooleanExpression) : StatementOk(trans,pc)
