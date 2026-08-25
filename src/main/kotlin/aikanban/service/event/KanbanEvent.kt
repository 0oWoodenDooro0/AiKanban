package aikanban.service.event

import aikanban.model.BoardColumn
import aikanban.model.Task
import aikanban.model.TaskLogEntry
import kotlinx.serialization.Serializable

@Serializable
sealed class KanbanEvent {
    @Serializable
    data class TaskCreated(val task: Task) : KanbanEvent()

    @Serializable
    data class TaskUpdated(val task: Task) : KanbanEvent()

    @Serializable
    data class TaskMoved(
        val task: Task,
        val fromStatus: String,
        val toStatus: String,
        val operator: String,
    ) : KanbanEvent()

    @Serializable
    data class TaskClaimed(
        val task: Task,
        val agentName: String,
    ) : KanbanEvent()

    @Serializable
    data class TaskReleased(
        val task: Task,
        val operator: String,
    ) : KanbanEvent()

    @Serializable
    data class TaskDeleted(val taskId: Int) : KanbanEvent()

    @Serializable
    data class TaskLogAdded(
        val taskId: Int,
        val entry: TaskLogEntry,
    ) : KanbanEvent()

    @Serializable
    data class ColumnCreated(val column: BoardColumn) : KanbanEvent()

    @Serializable
    data class ColumnUpdated(val column: BoardColumn) : KanbanEvent()

    @Serializable
    data class ColumnDeleted(val columnId: String) : KanbanEvent()
}
