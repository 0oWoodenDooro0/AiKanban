package aikanban.service

import aikanban.model.BoardColumn
import aikanban.model.Task
import aikanban.model.TaskLogEntry
import aikanban.model.TaskPriority
import aikanban.repository.TaskRepository
import aikanban.service.event.KanbanEvent
import aikanban.service.exception.ColumnNotFoundException
import aikanban.service.exception.ColumnValidationException
import aikanban.service.exception.TaskNotFoundException
import aikanban.service.exception.TaskValidationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class DefaultKanbanService(
    private val repository: TaskRepository,
) : KanbanService {
    private val _events = MutableSharedFlow<KanbanEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<KanbanEvent> = _events.asSharedFlow()

    // ==========================================
    // Board Column Management
    // ==========================================

    override fun getColumns(): List<BoardColumn> {
        return repository.getColumns()
    }

    override fun getColumn(id: String): BoardColumn? {
        return repository.getColumn(id)
    }

    override fun createColumn(column: BoardColumn): BoardColumn {
        if (column.id.isBlank()) {
            throw ColumnValidationException("Column ID cannot be blank")
        }
        if (column.name.isBlank()) {
            throw ColumnValidationException("Column name cannot be blank")
        }
        if (repository.getColumn(column.id) != null) {
            throw ColumnValidationException("Column with ID '${column.id}' already exists")
        }

        repository.saveColumn(column)
        _events.tryEmit(KanbanEvent.ColumnCreated(column))
        return repository.getColumn(column.id)
            ?: throw IllegalStateException("Failed to load created column ${column.id}")
    }

    override fun updateColumn(column: BoardColumn): BoardColumn {
        if (column.name.isBlank()) {
            throw ColumnValidationException("Column name cannot be blank")
        }
        if (repository.getColumn(column.id) == null) {
            throw ColumnNotFoundException(column.id)
        }

        repository.saveColumn(column)
        _events.tryEmit(KanbanEvent.ColumnUpdated(column))
        return repository.getColumn(column.id)
            ?: throw IllegalStateException("Failed to load updated column ${column.id}")
    }

    override fun deleteColumn(id: String): Boolean {
        val existing = repository.getColumn(id) ?: return false
        val activeTasks = repository.listTasks(status = id)
        if (activeTasks.isNotEmpty()) {
            throw ColumnValidationException("Cannot delete column '$id' because it contains active tasks")
        }

        val deleted = repository.deleteColumn(id)
        if (deleted) {
            _events.tryEmit(KanbanEvent.ColumnDeleted(id))
        }
        return deleted
    }

    // ==========================================
    // Task CRUD & Filtering
    // ==========================================

    override fun createTask(
        title: String,
        description: String,
        priority: TaskPriority,
        assignee: String?,
        tags: Set<String>,
        githubRepo: String?,
        githubIssueUrl: String?,
        status: String,
        operator: String,
    ): Task {
        if (title.isBlank()) {
            throw TaskValidationException("Task title cannot be blank")
        }
        if (repository.getColumn(status) == null) {
            throw ColumnNotFoundException(status)
        }

        val initialLog =
            TaskLogEntry(
                operator = operator,
                toStatus = status,
                comment = "Task created in column $status",
            )

        val task =
            Task(
                title = title.trim(),
                description = description.trim(),
                priority = priority,
                assignee = assignee,
                tags = tags,
                githubRepo = githubRepo,
                githubIssueUrl = githubIssueUrl,
                status = status,
                logs = listOf(initialLog),
            )

        val created = repository.createTask(task)
        _events.tryEmit(KanbanEvent.TaskCreated(created))
        return created
    }

    override fun getTask(id: Int): Task {
        return repository.getTask(id) ?: throw TaskNotFoundException(id)
    }

    override fun getTaskOrNull(id: Int): Task? {
        return repository.getTask(id)
    }

    override fun listTasks(
        status: String?,
        assignee: String?,
        tag: String?,
        priority: TaskPriority?,
    ): List<Task> {
        val tasks = repository.listTasks(status = status, assignee = assignee, tag = tag)
        return if (priority != null) {
            tasks.filter { it.priority == priority }
        } else {
            tasks
        }
    }

    override fun updateTask(
        taskId: Int,
        title: String?,
        description: String?,
        priority: TaskPriority?,
        assignee: String?,
        tags: Set<String>?,
        githubRepo: String?,
        githubIssueUrl: String?,
        githubPrUrl: String?,
        operator: String,
        comment: String?,
    ): Task {
        val current = getTask(taskId)
        if (title != null && title.isBlank()) {
            throw TaskValidationException("Task title cannot be blank")
        }

        val updated =
            current.copy(
                title = title?.trim() ?: current.title,
                description = description?.trim() ?: current.description,
                priority = priority ?: current.priority,
                assignee = assignee ?: current.assignee,
                tags = tags ?: current.tags,
                githubRepo = githubRepo ?: current.githubRepo,
                githubIssueUrl = githubIssueUrl ?: current.githubIssueUrl,
                githubPrUrl = githubPrUrl ?: current.githubPrUrl,
            )

        repository.updateTask(updated)

        if (comment != null || operator != "system") {
            val logEntry =
                TaskLogEntry(
                    operator = operator,
                    comment = comment ?: "Task updated",
                )
            repository.appendLog(taskId, logEntry)
        }

        val reloaded = getTask(taskId)
        _events.tryEmit(KanbanEvent.TaskUpdated(reloaded))
        return reloaded
    }

    override fun deleteTask(id: Int): Boolean {
        val existing = repository.getTask(id) ?: return false
        val deleted = repository.deleteTask(id)
        if (deleted) {
            _events.tryEmit(KanbanEvent.TaskDeleted(id))
        }
        return deleted
    }

    // ==========================================
    // Lifecycle & Workflow Movement
    // ==========================================

    override fun moveTask(
        taskId: Int,
        toStatus: String,
        operator: String,
        comment: String?,
        prUrl: String?,
        assignee: String?,
    ): Task {
        val current = getTask(taskId)
        val targetColumn = getColumn(toStatus) ?: throw ColumnNotFoundException(toStatus)

        val moved =
            repository.moveTask(
                taskId = taskId,
                toStatus = toStatus,
                operator = operator,
                comment = comment,
                prUrl = prUrl,
                assignee = assignee,
            )

        _events.tryEmit(
            KanbanEvent.TaskMoved(
                task = moved,
                fromStatus = current.status,
                toStatus = toStatus,
                operator = operator,
            ),
        )
        return moved
    }

    override fun claimNextTask(
        fromStatus: String,
        toStatus: String,
        agentName: String,
        tag: String?,
    ): Task? {
        getColumn(toStatus) ?: throw ColumnNotFoundException(toStatus)
        val claimed = repository.claimNextTask(fromStatus, toStatus, agentName, tag)
        if (claimed != null) {
            _events.tryEmit(KanbanEvent.TaskClaimed(claimed, agentName))
        }
        return claimed
    }

    override fun releaseTask(
        taskId: Int,
        operator: String,
        targetStatus: String,
        comment: String?,
    ): Task {
        val current = getTask(taskId)
        getColumn(targetStatus) ?: throw ColumnNotFoundException(targetStatus)

        val updated = current.copy(status = targetStatus, assignee = null, completedAt = null)
        repository.updateTask(updated)

        val logEntry =
            TaskLogEntry(
                operator = operator,
                fromStatus = current.status,
                toStatus = targetStatus,
                comment = comment ?: "Task released by $operator",
            )
        repository.appendLog(taskId, logEntry)

        val reloaded = getTask(taskId)
        _events.tryEmit(KanbanEvent.TaskReleased(reloaded, operator))
        return reloaded
    }

    override fun submitForReview(
        taskId: Int,
        agentName: String,
        prUrl: String?,
        comment: String?,
    ): Task {
        return moveTask(
            taskId = taskId,
            toStatus = "REVIEW",
            operator = agentName,
            comment = comment ?: "Submitted for review",
            prUrl = prUrl,
        )
    }

    override fun requestChanges(
        taskId: Int,
        reviewer: String,
        comment: String,
    ): Task {
        return moveTask(
            taskId = taskId,
            toStatus = "REQUEST",
            operator = reviewer,
            comment = comment,
        )
    }

    override fun markPending(
        taskId: Int,
        operator: String,
        comment: String,
    ): Task {
        return moveTask(
            taskId = taskId,
            toStatus = "PENDING",
            operator = operator,
            comment = comment,
        )
    }

    override fun approveAndComplete(
        taskId: Int,
        reviewer: String,
        comment: String?,
    ): Task {
        return moveTask(
            taskId = taskId,
            toStatus = "DONE",
            operator = reviewer,
            comment = comment ?: "Approved and completed",
        )
    }

    override fun reopenTask(
        taskId: Int,
        operator: String,
        comment: String?,
    ): Task {
        return moveTask(
            taskId = taskId,
            toStatus = "REOPEN",
            operator = operator,
            comment = comment ?: "Reopened by $operator",
        )
    }

    // ==========================================
    // Audit Logging
    // ==========================================

    override fun getTaskLogs(taskId: Int): List<TaskLogEntry> {
        val task = getTask(taskId)
        return task.logs
    }

    override fun addComment(
        taskId: Int,
        operator: String,
        comment: String,
        prUrl: String?,
        commitHash: String?,
    ): TaskLogEntry {
        getTask(taskId) // ensures task exists
        val entry =
            TaskLogEntry(
                operator = operator,
                comment = comment,
                prUrl = prUrl,
                commitHash = commitHash,
            )
        repository.appendLog(taskId, entry)
        _events.tryEmit(KanbanEvent.TaskLogAdded(taskId, entry))
        return entry
    }

    override fun close() {
        repository.close()
    }
}
