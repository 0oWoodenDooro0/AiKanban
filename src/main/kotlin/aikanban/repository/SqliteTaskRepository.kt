package aikanban.repository

import aikanban.model.BoardColumn
import aikanban.model.Task
import aikanban.model.TaskLogEntry
import aikanban.model.TaskPriority
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

class SqliteTaskRepository(
    private val jdbcUrl: String = "jdbc:sqlite:aikanban.db",
) : TaskRepository {
    private val connection: Connection = DriverManager.getConnection(jdbcUrl)
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        synchronized(lock) {
            connection.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode = WAL;")
                stmt.execute("PRAGMA foreign_keys = ON;")
                stmt.execute("PRAGMA busy_timeout = 5000;")
            }
            createTables()
            migrateSchema()
            createIndexes()
            initDefaultColumns()
        }
    }

    private fun createTables() {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS columns (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    display_order INTEGER NOT NULL,
                    color TEXT NOT NULL,
                    is_terminal INTEGER NOT NULL DEFAULT 0
                );
                """.trimIndent(),
            )

            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS tasks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL,
                    status TEXT NOT NULL,
                    priority TEXT NOT NULL,
                    assignee TEXT,
                    tags TEXT NOT NULL,
                    branch TEXT,
                    github_repo TEXT,
                    github_issue_url TEXT,
                    github_pr_url TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    completed_at INTEGER,
                    FOREIGN KEY(status) REFERENCES columns(id) ON UPDATE CASCADE
                );
                """.trimIndent(),
            )

            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS task_logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    operator TEXT NOT NULL,
                    from_status TEXT,
                    to_status TEXT,
                    comment TEXT NOT NULL,
                    pr_url TEXT,
                    commit_hash TEXT,
                    FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE CASCADE
                );
                """.trimIndent(),
            )
        }
    }

    private fun migrateSchema() {
        connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("PRAGMA table_info(tasks);")
            val columns = mutableListOf<String>()
            while (rs.next()) {
                columns.add(rs.getString("name"))
            }
            if (columns.isNotEmpty() && !columns.contains("branch")) {
                stmt.execute("ALTER TABLE tasks ADD COLUMN branch TEXT;")
            }
        }
    }

    private fun createIndexes() {
        connection.createStatement().use { stmt ->
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tasks_assignee ON tasks(assignee);")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tasks_branch ON tasks(branch);")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_task_logs_task_id ON task_logs(task_id);")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_columns_order ON columns(display_order);")
        }
    }

    override fun initDefaultColumns() {
        synchronized(lock) {
            val count =
                connection.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT COUNT(*) FROM columns;")
                    if (rs.next()) rs.getInt(1) else 0
                }
            if (count == 0) {
                BoardColumn.DEFAULT_COLUMNS.forEach { saveColumn(it) }
            }
        }
    }

    override fun getColumns(): List<BoardColumn> {
        synchronized(lock) {
            val list = mutableListOf<BoardColumn>()
            connection.prepareStatement("SELECT id, name, display_order, color, is_terminal FROM columns ORDER BY display_order ASC;").use {
                    stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        BoardColumn(
                            id = rs.getString("id"),
                            name = rs.getString("name"),
                            order = rs.getInt("display_order"),
                            color = rs.getString("color"),
                            isTerminal = rs.getInt("is_terminal") == 1,
                        ),
                    )
                }
            }
            return list
        }
    }

    override fun getColumn(id: String): BoardColumn? {
        synchronized(lock) {
            connection.prepareStatement("SELECT id, name, display_order, color, is_terminal FROM columns WHERE id = ?;").use { stmt ->
                stmt.setString(1, id)
                val rs = stmt.executeQuery()
                return if (rs.next()) {
                    BoardColumn(
                        id = rs.getString("id"),
                        name = rs.getString("name"),
                        order = rs.getInt("display_order"),
                        color = rs.getString("color"),
                        isTerminal = rs.getInt("is_terminal") == 1,
                    )
                } else {
                    null
                }
            }
        }
    }

    override fun saveColumn(column: BoardColumn) {
        synchronized(lock) {
            val sql =
                """
                INSERT INTO columns (id, name, display_order, color, is_terminal)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    display_order = excluded.display_order,
                    color = excluded.color,
                    is_terminal = excluded.is_terminal;
                """.trimIndent()
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, column.id)
                stmt.setString(2, column.name)
                stmt.setInt(3, column.order)
                stmt.setString(4, column.color)
                stmt.setInt(5, if (column.isTerminal) 1 else 0)
                stmt.executeUpdate()
            }
        }
    }

    override fun deleteColumn(id: String): Boolean {
        synchronized(lock) {
            connection.prepareStatement("DELETE FROM columns WHERE id = ?;").use { stmt ->
                stmt.setString(1, id)
                return stmt.executeUpdate() > 0
            }
        }
    }

    override fun createTask(task: Task): Task {
        synchronized(lock) {
            val sql =
                """
                INSERT INTO tasks (
                    title, description, status, priority, assignee, tags, branch,
                    github_repo, github_issue_url, github_pr_url,
                    created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """.trimIndent()

            val now = System.currentTimeMillis()
            val createdAt = if (task.createdAt > 0) task.createdAt else now
            val updatedAt = if (task.updatedAt > 0) task.updatedAt else now

            val generatedId =
                connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { stmt ->
                    stmt.setString(1, task.title)
                    stmt.setString(2, task.description)
                    stmt.setString(3, task.status)
                    stmt.setString(4, task.priority.name)
                    stmt.setString(5, task.assignee)
                    stmt.setString(6, json.encodeToString(task.tags))
                    stmt.setString(7, task.branch)
                    stmt.setString(8, task.githubRepo)
                    stmt.setString(9, task.githubIssueUrl)
                    stmt.setString(10, task.githubPrUrl)
                    stmt.setLong(11, createdAt)
                    stmt.setLong(12, updatedAt)
                    if (task.completedAt != null) {
                        stmt.setLong(13, task.completedAt)
                    } else {
                        stmt.setNull(13, java.sql.Types.INTEGER)
                    }
                    stmt.executeUpdate()

                    val rs = stmt.generatedKeys
                    if (rs.next()) rs.getInt(1) else throw IllegalStateException("Failed to retrieve generated task ID")
                }

            task.logs.forEach { logEntry ->
                appendLogInternal(generatedId, logEntry)
            }

            return getTask(generatedId) ?: throw IllegalStateException("Failed to load created task")
        }
    }

    override fun getTask(id: Int): Task? {
        synchronized(lock) {
            val sql = "SELECT * FROM tasks WHERE id = ?;"
            val task =
                connection.prepareStatement(sql).use { stmt ->
                    stmt.setInt(1, id)
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        mapTask(rs)
                    } else {
                        null
                    }
                } ?: return null

            val logs = getTaskLogs(id)
            return task.copy(logs = logs)
        }
    }

    override fun listTasks(
        status: String?,
        assignee: String?,
        tag: String?,
    ): List<Task> {
        synchronized(lock) {
            val conditions = mutableListOf<String>()
            val params = mutableListOf<Any>()

            if (status != null) {
                conditions.add("status = ?")
                params.add(status)
            }
            if (assignee != null) {
                conditions.add("assignee = ?")
                params.add(assignee)
            }

            val whereClause = if (conditions.isNotEmpty()) "WHERE " + conditions.joinToString(" AND ") else ""
            val sql = "SELECT * FROM tasks $whereClause ORDER BY created_at ASC;"

            val tasks = mutableListOf<Task>()
            connection.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { index, param ->
                    when (param) {
                        is String -> stmt.setString(index + 1, param)
                        is Int -> stmt.setInt(index + 1, param)
                        is Long -> stmt.setLong(index + 1, param)
                    }
                }
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    tasks.add(mapTask(rs))
                }
            }

            val filtered =
                if (tag != null) {
                    tasks.filter { it.tags.contains(tag) }
                } else {
                    tasks
                }

            return filtered.map { task ->
                task.copy(logs = getTaskLogs(task.id))
            }
        }
    }

    override fun updateTask(task: Task): Task {
        synchronized(lock) {
            val sql =
                """
                UPDATE tasks SET
                    title = ?,
                    description = ?,
                    status = ?,
                    priority = ?,
                    assignee = ?,
                    tags = ?,
                    branch = ?,
                    github_repo = ?,
                    github_issue_url = ?,
                    github_pr_url = ?,
                    updated_at = ?,
                    completed_at = ?
                WHERE id = ?;
                """.trimIndent()

            val now = System.currentTimeMillis()
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, task.title)
                stmt.setString(2, task.description)
                stmt.setString(3, task.status)
                stmt.setString(4, task.priority.name)
                stmt.setString(5, task.assignee)
                stmt.setString(6, json.encodeToString(task.tags))
                stmt.setString(7, task.branch)
                stmt.setString(8, task.githubRepo)
                stmt.setString(9, task.githubIssueUrl)
                stmt.setString(10, task.githubPrUrl)
                stmt.setLong(11, now)
                if (task.completedAt != null) {
                    stmt.setLong(12, task.completedAt)
                } else {
                    stmt.setNull(12, java.sql.Types.INTEGER)
                }
                stmt.setInt(13, task.id)
                stmt.executeUpdate()
            }

            val existingLogs = getTaskLogs(task.id)
            if (task.logs.size > existingLogs.size && task.logs.subList(0, existingLogs.size) == existingLogs) {
                val newLogs = task.logs.subList(existingLogs.size, task.logs.size)
                newLogs.forEach { logEntry ->
                    appendLogInternal(task.id, logEntry)
                }
            } else if (task.logs != existingLogs) {
                connection.prepareStatement("DELETE FROM task_logs WHERE task_id = ?;").use { stmt ->
                    stmt.setInt(1, task.id)
                    stmt.executeUpdate()
                }
                task.logs.forEach { logEntry ->
                    appendLogInternal(task.id, logEntry)
                }
            }

            return getTask(task.id) ?: throw IllegalStateException("Task ${task.id} not found after update")
        }
    }

    override fun deleteTask(id: Int): Boolean {
        synchronized(lock) {
            // Delete logs first for cascade safety
            connection.prepareStatement("DELETE FROM task_logs WHERE task_id = ?;").use { stmt ->
                stmt.setInt(1, id)
                stmt.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM tasks WHERE id = ?;").use { stmt ->
                stmt.setInt(1, id)
                return stmt.executeUpdate() > 0
            }
        }
    }

    private fun appendLogInternal(
        taskId: Int,
        entry: TaskLogEntry,
    ) {
        val sql =
            """
            INSERT INTO task_logs (
                task_id, timestamp, operator, from_status, to_status,
                comment, pr_url, commit_hash
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?);
            """.trimIndent()

        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, taskId)
            stmt.setLong(2, if (entry.timestamp > 0) entry.timestamp else System.currentTimeMillis())
            stmt.setString(3, entry.operator)
            stmt.setString(4, entry.fromStatus)
            stmt.setString(5, entry.toStatus)
            stmt.setString(6, entry.comment)
            stmt.setString(7, entry.prUrl)
            stmt.setString(8, entry.commitHash)
            stmt.executeUpdate()
        }
    }

    private fun getTaskLogs(taskId: Int): List<TaskLogEntry> {
        val sql = "SELECT * FROM task_logs WHERE task_id = ? ORDER BY timestamp ASC, id ASC;"
        val list = mutableListOf<TaskLogEntry>()
        connection.prepareStatement(sql).use { stmt ->
            stmt.setInt(1, taskId)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                list.add(
                    TaskLogEntry(
                        timestamp = rs.getLong("timestamp"),
                        operator = rs.getString("operator"),
                        fromStatus = rs.getString("from_status"),
                        toStatus = rs.getString("to_status"),
                        comment = rs.getString("comment"),
                        prUrl = rs.getString("pr_url"),
                        commitHash = rs.getString("commit_hash"),
                    ),
                )
            }
        }
        return list
    }

    private fun mapTask(rs: ResultSet): Task {
        val tagsJson = rs.getString("tags") ?: "[]"
        val tags =
            try {
                json.decodeFromString<Set<String>>(tagsJson)
            } catch (_: Exception) {
                emptySet()
            }

        val completedAtVal = rs.getLong("completed_at")
        val completedAt = if (rs.wasNull()) null else completedAtVal

        val branch =
            try {
                rs.getString("branch")
            } catch (_: Exception) {
                null
            }

        return Task(
            id = rs.getInt("id"),
            title = rs.getString("title"),
            description = rs.getString("description"),
            status = rs.getString("status"),
            priority = TaskPriority.valueOf(rs.getString("priority")),
            assignee = rs.getString("assignee"),
            tags = tags,
            branch = branch,
            githubRepo = rs.getString("github_repo"),
            githubIssueUrl = rs.getString("github_issue_url"),
            githubPrUrl = rs.getString("github_pr_url"),
            logs = emptyList(),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            completedAt = completedAt,
        )
    }

    override fun close() {
        synchronized(lock) {
            if (!connection.isClosed) {
                connection.close()
            }
        }
    }
}
