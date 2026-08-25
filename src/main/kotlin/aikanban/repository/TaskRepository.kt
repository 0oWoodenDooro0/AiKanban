package aikanban.repository

import aikanban.model.BoardColumn
import aikanban.model.Task
import aikanban.model.TaskLogEntry

interface TaskRepository : AutoCloseable {
    // Columns
    fun getColumns(): List<BoardColumn>
    fun getColumn(id: String): BoardColumn?
    fun saveColumn(column: BoardColumn)
    fun deleteColumn(id: String): Boolean
    fun initDefaultColumns()

    // Tasks
    fun createTask(task: Task): Task
    fun getTask(id: Int): Task?
    fun listTasks(
        status: String? = null,
        assignee: String? = null,
        tag: String? = null
    ): List<Task>
    fun updateTask(task: Task): Task
    fun deleteTask(id: Int): Boolean

    // Workflow & Atomic Claiming
    fun claimNextTask(
        fromStatus: String = "TODO",
        toStatus: String = "IN_PROGRESS",
        agentName: String,
        tag: String? = null
    ): Task?

    fun moveTask(
        taskId: Int,
        toStatus: String,
        operator: String,
        comment: String? = null,
        prUrl: String? = null,
        assignee: String? = null
    ): Task

    fun appendLog(taskId: Int, entry: TaskLogEntry)
}
