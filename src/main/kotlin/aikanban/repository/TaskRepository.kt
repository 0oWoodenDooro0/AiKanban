package aikanban.repository

import aikanban.model.BoardColumn
import aikanban.model.Task

interface TaskRepository : AutoCloseable {
    // Columns
    fun getColumns(): List<BoardColumn>

    fun getColumn(id: String): BoardColumn?

    fun saveColumn(column: BoardColumn)

    fun deleteColumn(id: String): Boolean

    fun initDefaultColumns()

    // Tasks (Pure CRUD)
    fun createTask(task: Task): Task

    fun getTask(id: Int): Task?

    fun listTasks(
        status: String? = null,
        assignee: String? = null,
        tag: String? = null,
    ): List<Task>

    fun updateTask(task: Task): Task

    fun deleteTask(id: Int): Boolean
}
