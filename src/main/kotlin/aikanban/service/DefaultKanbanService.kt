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
    private val lock = Any()
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
        synchronized(lock) {
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
    }

    override fun updateColumn(column: BoardColumn): BoardColumn {
        synchronized(lock) {
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
    }

    override fun deleteColumn(id: String): Boolean {
        synchronized(lock) {
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
        branch: String?,
        githubRepo: String?,
        githubIssueUrl: String?,
        status: String,
        operator: String,
    ): Task {
        synchronized(lock) {
            if (title.isBlank()) {
                throw TaskValidationException("Task title cannot be blank")
            }
            val targetColumn = repository.getColumn(status) ?: throw ColumnNotFoundException(status)

            val now = System.currentTimeMillis()
            val initialLog =
                TaskLogEntry(
                    timestamp = now,
                    operator = operator,
                    toStatus = status,
                    comment = "Task created in column $status",
                )

            val completedAt = if (targetColumn.isTerminal) now else null

            val task =
                Task(
                    title = title.trim(),
                    description = description.trim(),
                    priority = priority,
                    assignee = assignee,
                    tags = tags,
                    branch = branch?.trim()?.takeIf { it.isNotBlank() },
                    githubRepo = githubRepo,
                    githubIssueUrl = githubIssueUrl,
                    status = status,
                    logs = listOf(initialLog),
                    createdAt = now,
                    updatedAt = now,
                    completedAt = completedAt,
                )

            val created = repository.createTask(task)
            _events.tryEmit(KanbanEvent.TaskCreated(created))
            return created
        }
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
        branch: String?,
        githubRepo: String?,
        githubIssueUrl: String?,
        githubPrUrl: String?,
        operator: String,
        comment: String?,
    ): Task {
        synchronized(lock) {
            val current = getTask(taskId)
            if (title != null && title.isBlank()) {
                throw TaskValidationException("Task title cannot be blank")
            }

            val now = System.currentTimeMillis()
            val newLogs = current.logs.toMutableList()
            if (comment != null || operator != "system") {
                newLogs.add(
                    TaskLogEntry(
                        timestamp = now,
                        operator = operator,
                        comment = comment ?: "Task updated",
                    ),
                )
            }

            val updated =
                current.copy(
                    title = title?.trim() ?: current.title,
                    description = description?.trim() ?: current.description,
                    priority = priority ?: current.priority,
                    assignee = assignee ?: current.assignee,
                    tags = tags ?: current.tags,
                    branch = branch?.trim()?.takeIf { it.isNotBlank() } ?: current.branch,
                    githubRepo = githubRepo ?: current.githubRepo,
                    githubIssueUrl = githubIssueUrl ?: current.githubIssueUrl,
                    githubPrUrl = githubPrUrl ?: current.githubPrUrl,
                    logs = newLogs,
                    updatedAt = now,
                )

            val saved = repository.updateTask(updated)
            _events.tryEmit(KanbanEvent.TaskUpdated(saved))
            return saved
        }
    }

    override fun deleteTask(id: Int): Boolean {
        synchronized(lock) {
            val existing = repository.getTask(id) ?: return false
            val deleted = repository.deleteTask(id)
            if (deleted) {
                _events.tryEmit(KanbanEvent.TaskDeleted(id))
            }
            return deleted
        }
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
        synchronized(lock) {
            val current = getTask(taskId)
            val targetColumn = getColumn(toStatus) ?: throw ColumnNotFoundException(toStatus)

            val isTerminal = targetColumn.isTerminal
            val now = System.currentTimeMillis()
            val newCompletedAt: Long? =
                when {
                    isTerminal -> current.completedAt ?: now
                    else -> null
                }
            val newAssignee = assignee ?: current.assignee
            val newPrUrl = prUrl ?: current.githubPrUrl

            val logComment = comment ?: "Status changed from ${current.status} to $toStatus"
            val logEntry =
                TaskLogEntry(
                    timestamp = now,
                    operator = operator,
                    fromStatus = current.status,
                    toStatus = toStatus,
                    comment = logComment,
                    prUrl = prUrl,
                )

            val updated =
                current.copy(
                    status = toStatus,
                    assignee = newAssignee,
                    githubPrUrl = newPrUrl,
                    completedAt = newCompletedAt,
                    logs = current.logs + logEntry,
                    updatedAt = now,
                )

            val saved = repository.updateTask(updated)
            _events.tryEmit(
                KanbanEvent.TaskMoved(
                    task = saved,
                    fromStatus = current.status,
                    toStatus = toStatus,
                    operator = operator,
                ),
            )
            return saved
        }
    }

    override fun claimNextTask(
        fromStatus: String,
        toStatus: String,
        agentName: String,
        tag: String?,
    ): Task? {
        synchronized(lock) {
            val targetColumn = getColumn(toStatus) ?: throw ColumnNotFoundException(toStatus)
            val candidateTasks = repository.listTasks(status = fromStatus)
            val unassigned = candidateTasks.filter { it.assignee.isNullOrBlank() }
            val matchingTag = if (tag != null) unassigned.filter { it.tags.contains(tag) } else unassigned

            val candidate =
                matchingTag.minWithOrNull(
                    compareBy<Task> { task ->
                        when (task.priority) {
                            TaskPriority.URGENT -> 1
                            TaskPriority.HIGH -> 2
                            TaskPriority.MEDIUM -> 3
                            TaskPriority.LOW -> 4
                        }
                    }.thenBy { it.createdAt },
                ) ?: return null

            val isTerminal = targetColumn.isTerminal
            val now = System.currentTimeMillis()
            val newCompletedAt: Long? =
                when {
                    isTerminal -> candidate.completedAt ?: now
                    else -> null
                }

            val logEntry =
                TaskLogEntry(
                    timestamp = now,
                    operator = agentName,
                    fromStatus = fromStatus,
                    toStatus = toStatus,
                    comment = "Task claimed by $agentName",
                )

            val updated =
                candidate.copy(
                    status = toStatus,
                    assignee = agentName,
                    completedAt = newCompletedAt,
                    logs = candidate.logs + logEntry,
                    updatedAt = now,
                )

            val saved = repository.updateTask(updated)
            _events.tryEmit(KanbanEvent.TaskClaimed(saved, agentName))
            return saved
        }
    }

    override fun releaseTask(
        taskId: Int,
        operator: String,
        targetStatus: String,
        comment: String?,
    ): Task {
        synchronized(lock) {
            val current = getTask(taskId)
            val targetColumn = getColumn(targetStatus) ?: throw ColumnNotFoundException(targetStatus)

            val isTerminal = targetColumn.isTerminal
            val now = System.currentTimeMillis()
            val newCompletedAt: Long? =
                when {
                    isTerminal -> current.completedAt ?: now
                    else -> null
                }

            val logEntry =
                TaskLogEntry(
                    timestamp = now,
                    operator = operator,
                    fromStatus = current.status,
                    toStatus = targetStatus,
                    comment = comment ?: "Task released by $operator",
                )

            val updated =
                current.copy(
                    status = targetStatus,
                    assignee = null,
                    completedAt = newCompletedAt,
                    logs = current.logs + logEntry,
                    updatedAt = now,
                )

            val saved = repository.updateTask(updated)
            _events.tryEmit(KanbanEvent.TaskReleased(saved, operator))
            return saved
        }
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
        synchronized(lock) {
            val current = getTask(taskId)
            val now = System.currentTimeMillis()
            val entry =
                TaskLogEntry(
                    timestamp = now,
                    operator = operator,
                    comment = comment,
                    prUrl = prUrl,
                    commitHash = commitHash,
                )
            val updated = current.copy(logs = current.logs + entry, updatedAt = now)
            repository.updateTask(updated)
            _events.tryEmit(KanbanEvent.TaskLogAdded(taskId, entry))
            return entry
        }
    }

    override fun close() {
        repository.close()
    }
}
