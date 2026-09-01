package aikanban.service

import aikanban.model.BoardColumn
import aikanban.model.Task
import aikanban.model.TaskLogEntry
import aikanban.model.TaskPriority
import aikanban.model.TaskQuery
import aikanban.model.TaskSortBy
import aikanban.service.event.KanbanEvent
import kotlinx.coroutines.flow.SharedFlow

interface KanbanService : AutoCloseable {
    val events: SharedFlow<KanbanEvent>

    // Columns
    fun getColumns(): List<BoardColumn>

    fun getColumn(id: String): BoardColumn?

    fun createColumn(column: BoardColumn): BoardColumn

    fun updateColumn(column: BoardColumn): BoardColumn

    fun deleteColumn(id: String): Boolean

    // Tasks CRUD & Queries
    fun createTask(
        title: String,
        description: String = "",
        priority: TaskPriority = TaskPriority.MEDIUM,
        assignee: String? = null,
        tags: Set<String> = emptySet(),
        branch: String? = null,
        githubRepo: String? = null,
        githubIssueUrl: String? = null,
        status: String = "TODO",
        operator: String = "system",
    ): Task

    fun getTask(id: Int): Task

    fun getTaskOrNull(id: Int): Task?

    fun listTasks(query: TaskQuery = TaskQuery()): List<Task>

    fun listTasks(
        status: String? = null,
        assignee: String? = null,
        tag: String? = null,
        priority: TaskPriority? = null,
        includeCompleted: Boolean = false,
        sortBy: TaskSortBy = TaskSortBy.PRIORITY,
    ): List<Task> =
        listTasks(
            TaskQuery(
                status = status,
                assignee = assignee,
                tag = tag,
                priority = priority,
                includeCompleted = includeCompleted,
                sortBy = sortBy,
            ),
        )

    fun updateTask(
        taskId: Int,
        title: String? = null,
        description: String? = null,
        priority: TaskPriority? = null,
        assignee: String? = null,
        tags: Set<String>? = null,
        branch: String? = null,
        githubRepo: String? = null,
        githubIssueUrl: String? = null,
        githubPrUrl: String? = null,
        operator: String = "system",
        comment: String? = null,
    ): Task

    fun deleteTask(id: Int): Boolean

    // Lifecycle & Workflow Operations
    fun moveTask(
        taskId: Int,
        toStatus: String,
        operator: String,
        comment: String? = null,
        prUrl: String? = null,
        assignee: String? = null,
    ): Task

    fun claimNextTask(
        fromStatus: String = "TODO",
        toStatus: String = "IN_PROGRESS",
        agentName: String,
        tag: String? = null,
    ): Task?

    fun releaseTask(
        taskId: Int,
        operator: String,
        targetStatus: String = "TODO",
        comment: String? = null,
    ): Task

    // Audit Logging
    fun getTaskLogs(taskId: Int): List<TaskLogEntry>

    fun addComment(
        taskId: Int,
        operator: String,
        comment: String,
        prUrl: String? = null,
        commitHash: String? = null,
    ): TaskLogEntry
}
