package tools.aqua.wvm.machine

import tools.aqua.konstraints.smt.SatStatus
import tools.aqua.wvm.analysis.hoare.SMTSolver
import tools.aqua.wvm.analysis.semantics.StatementApp
import tools.aqua.wvm.language.And
import tools.aqua.wvm.language.BooleanExpression
import tools.aqua.wvm.language.Not

data class ExecutionTree(
  val next: MutableMap<StatementApp, ExecutionTree>,
  val cfg: Configuration,
  val stepCount: Int = 0,
  val output: String? = null
) {
  fun size(): Int = next.size

  fun toIndentString(indent: String): String {
    val memoryState = cfg.toString()
    val out = if (output != null) "with message \"$output\"\n" else "\n"
    val children = next.values.map { it.toIndentString(indent + "  ") }.joinToString("\n") { it }
    return "$indent$memoryState $out$children"
  }

  fun unsafePaths(postCondition : BooleanExpression): Set<ExecutionTree> {
    if(next.isEmpty()) {
      val smtSolver = SMTSolver()
      val pc =
        postCondition.evaluate(this.cfg.scope, this.cfg.memory, this.cfg.pathConstraint)
        .map { it.result }
        .reduce{ acc, exp -> And(acc, exp) }
      val constraint = And(this.cfg.pathConstraint, Not(pc))
      val result = smtSolver.solve(constraint)
      if (result.status != SatStatus.UNSAT || this.cfg.error) {
        return setOf(this)
      } else {
        return setOf()
      }
    } else {
      return this
        .next
        .values
        .map{
          it.unsafePaths(postCondition)
        }
        .reduce {acc, s -> acc + s}
    }
  }

  fun flatten() : List<StatementApp> {
    if (next.size == 0) return listOf()
    if (next.size != 1) throw Exception("Execution Tree of size ${next.size} cannot be flatten.")
    return next.keys.toList() + next.values.first().flatten()
  }
}
