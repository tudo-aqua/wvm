package tools.aqua.wvm.machine

import tools.aqua.wvm.analysis.semantics.StatementApp

data class ExecutionTree(
  val next: MutableMap<StatementApp, ExecutionTree>,
  val cfg: Configuration,
  val stepCount: Int = 0
) {
  fun size() : Int = next.size
}
