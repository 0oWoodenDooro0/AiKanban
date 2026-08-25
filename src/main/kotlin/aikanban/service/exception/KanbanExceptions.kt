package aikanban.service.exception

open class KanbanException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class TaskNotFoundException(val taskId: Int) : KanbanException("Task with id $taskId not found")

class ColumnNotFoundException(val columnId: String) : KanbanException("Column with id '$columnId' not found")

class TaskValidationException(val validationMessage: String) : KanbanException(validationMessage)

class ColumnValidationException(val validationMessage: String) : KanbanException(validationMessage)

class TaskAlreadyClaimedException(val taskId: Int, val currentAssignee: String?) :
    KanbanException("Task with id $taskId is already claimed by ${currentAssignee ?: "another agent"}")
