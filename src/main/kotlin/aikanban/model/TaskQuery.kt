package aikanban.model

import kotlinx.serialization.Serializable

@Serializable
enum class TaskSortBy {
    PRIORITY,
    ID,
    ID_DESC,
    CREATED_AT,
    CREATED_AT_DESC,
    UPDATED_AT,
    UPDATED_AT_DESC,
}

@Serializable
data class TaskQuery(
    val status: String? = null,
    val assignee: String? = null,
    val tag: String? = null,
    val priority: TaskPriority? = null,
    val includeCompleted: Boolean = false,
    val sortBy: TaskSortBy = TaskSortBy.PRIORITY,
)
