package tools.aqua.wvm.machine

import tools.aqua.wvm.analysis.semantics.StatementApp

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
}
