package aikanban.model

import kotlinx.serialization.Serializable

@Serializable
data class BoardColumn(
    val id: String,
    val name: String,
    val order: Int,
    val color: String = "#6B7280",
    val isTerminal: Boolean = false
) {
    companion object {
        val TODO = BoardColumn("TODO", "To Do", 0, "#6B7280", isTerminal = false)
        val IN_PROGRESS = BoardColumn("IN_PROGRESS", "In Progress", 1, "#3B82F6", isTerminal = false)
        val PR_REVIEW = BoardColumn("PR_REVIEW", "PR Review", 2, "#8B5CF6", isTerminal = false)
        val REQUEST = BoardColumn("REQUEST", "Pending Request", 3, "#F59E0B", isTerminal = false)
        val DONE = BoardColumn("DONE", "Done", 4, "#10B981", isTerminal = true)

        val DEFAULT_COLUMNS = listOf(TODO, IN_PROGRESS, PR_REVIEW, REQUEST, DONE)
    }
}
