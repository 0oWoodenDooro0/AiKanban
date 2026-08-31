package aikanban.model

import kotlinx.serialization.Serializable

@Serializable
data class Task(
    val id: Int = 0,
    val title: String,
    val description: String = "",
    val status: String = BoardColumn.TODO.id,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val assignee: String? = null,
    val tags: Set<String> = emptySet(),
    val branch: String? = null,
    val githubRepo: String? = null,
    val githubIssueUrl: String? = null,
    val githubPrUrl: String? = null,
    val logs: List<TaskLogEntry> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
)
