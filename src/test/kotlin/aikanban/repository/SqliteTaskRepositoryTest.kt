package aikanban.repository

import aikanban.model.BoardColumn
import aikanban.model.Task
import aikanban.model.TaskLogEntry
import aikanban.model.TaskPriority
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteTaskRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var repository: SqliteTaskRepository

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("test_kanban.db").toFile()
        repository = SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}")
    }

    @AfterEach
    fun tearDown() {
        repository.close()
    }

    @Test
    @DisplayName("Should initialize default columns on startup")
    fun testDefaultColumnsInitialization() {
        val columns = repository.getColumns()
        assertEquals(7, columns.size)
        assertEquals(listOf("TODO", "IN_PROGRESS", "REVIEW", "REQUEST", "PENDING", "REOPEN", "DONE"), columns.map { it.id })

        val doneCol = repository.getColumn("DONE")
        assertNotNull(doneCol)
        assertTrue(doneCol.isTerminal)

        val todoCol = repository.getColumn("TODO")
        assertNotNull(todoCol)
        assertFalse(todoCol.isTerminal)
    }

    @Test
    @DisplayName("Should support custom column CRUD operations and ordering")
    fun testCustomColumnCrud() {
        val customCol =
            BoardColumn(
                id = "QA",
                name = "Quality Assurance",
                order = 5,
                color = "#A855F7",
                isTerminal = false,
            )
        repository.saveColumn(customCol)

        val fetched = repository.getColumn("QA")
        assertNotNull(fetched)
        assertEquals("Quality Assurance", fetched.name)
        assertEquals(5, fetched.order)
        assertEquals("#A855F7", fetched.color)
        assertFalse(fetched.isTerminal)

        // Update column
        val updatedCol = customCol.copy(name = "QA & Testing", order = 2)
        repository.saveColumn(updatedCol)

        val fetchedUpdated = repository.getColumn("QA")
        assertNotNull(fetchedUpdated)
        assertEquals("QA & Testing", fetchedUpdated.name)
        assertEquals(2, fetchedUpdated.order)

        // Verify sorted ordering
        val allColumns = repository.getColumns()
        val qaIndex = allColumns.indexOfFirst { it.id == "QA" }
        assertEquals(2, allColumns[qaIndex].order)

        // Delete column
        val deleted = repository.deleteColumn("QA")
        assertTrue(deleted)
        assertNull(repository.getColumn("QA"))

        // Delete non-existent column returns false
        assertFalse(repository.deleteColumn("NON_EXISTENT"))
    }

    @Test
    @DisplayName("Should create, retrieve, update and delete a Task")
    fun testTaskCrud() {
        val newTask =
            Task(
                title = "Implement OAuth2 login",
                description = "Support Google & GitHub SSO",
                status = "TODO",
                priority = TaskPriority.HIGH,
                assignee = "agent-claude",
                tags = setOf("auth", "security", "backend"),
                githubRepo = "0oWoodenDooro0/AiKanban",
                githubIssueUrl = "https://github.com/0oWoodenDooro0/AiKanban/issues/10",
            )

        val created = repository.createTask(newTask)
        assertTrue(created.id > 0)
        assertEquals("Implement OAuth2 login", created.title)
        assertEquals(TaskPriority.HIGH, created.priority)
        assertEquals("agent-claude", created.assignee)
        assertEquals(setOf("auth", "security", "backend"), created.tags)
        assertEquals("0oWoodenDooro0/AiKanban", created.githubRepo)
        assertEquals("https://github.com/0oWoodenDooro0/AiKanban/issues/10", created.githubIssueUrl)
        assertNull(created.completedAt)

        // Retrieve task
        val retrieved = repository.getTask(created.id)
        assertNotNull(retrieved)
        assertEquals(created.id, retrieved.id)
        assertEquals(created.title, retrieved.title)
        assertEquals(created.tags, retrieved.tags)

        // Update task
        val taskToUpdate =
            retrieved.copy(
                title = "Implement OAuth2 & SAML login",
                priority = TaskPriority.URGENT,
                tags = setOf("auth", "enterprise"),
            )
        val updated = repository.updateTask(taskToUpdate)
        assertEquals("Implement OAuth2 & SAML login", updated.title)
        assertEquals(TaskPriority.URGENT, updated.priority)
        assertEquals(setOf("auth", "enterprise"), updated.tags)

        // Delete task
        val deleted = repository.deleteTask(created.id)
        assertTrue(deleted)
        assertNull(repository.getTask(created.id))
        assertFalse(repository.deleteTask(999999))
    }

    @Test
    @DisplayName("Should list and filter tasks by status, assignee, and tag")
    fun testListTasksFiltering() {
        val t1 = repository.createTask(Task(title = "Task 1", status = "TODO", assignee = "alice", tags = setOf("backend", "api")))
        val t2 = repository.createTask(Task(title = "Task 2", status = "IN_PROGRESS", assignee = "bob", tags = setOf("frontend", "ui")))
        val t3 = repository.createTask(Task(title = "Task 3", status = "TODO", assignee = "bob", tags = setOf("backend", "db")))
        val t4 = repository.createTask(Task(title = "Task 4", status = "DONE", assignee = "alice", tags = setOf("docs")))

        // Filter by status
        val todoTasks = repository.listTasks(status = "TODO")
        assertEquals(2, todoTasks.size)
        assertTrue(todoTasks.any { it.id == t1.id })
        assertTrue(todoTasks.any { it.id == t3.id })

        // Filter by assignee
        val bobTasks = repository.listTasks(assignee = "bob")
        assertEquals(2, bobTasks.size)
        assertTrue(bobTasks.any { it.id == t2.id })
        assertTrue(bobTasks.any { it.id == t3.id })

        // Filter by tag
        val backendTasks = repository.listTasks(tag = "backend")
        assertEquals(2, backendTasks.size)
        assertTrue(backendTasks.any { it.id == t1.id })
        assertTrue(backendTasks.any { it.id == t3.id })

        // Combined filter
        val bobBackendTasks = repository.listTasks(status = "TODO", assignee = "bob", tag = "backend")
        assertEquals(1, bobBackendTasks.size)
        assertEquals(t3.id, bobBackendTasks.first().id)
    }

    @Test
    @DisplayName("Should persist initial task logs and synchronize appended logs on update")
    fun testTaskLogsPersistenceAndSync() {
        val initialLog =
            TaskLogEntry(
                operator = "system",
                toStatus = "TODO",
                comment = "Task created",
            )
        val task =
            repository.createTask(
                Task(
                    title = "Task with logs",
                    status = "TODO",
                    logs = listOf(initialLog),
                ),
            )

        val retrieved1 = repository.getTask(task.id)
        assertNotNull(retrieved1)
        assertEquals(1, retrieved1.logs.size)
        assertEquals("system", retrieved1.logs[0].operator)
        assertEquals("Task created", retrieved1.logs[0].comment)

        // Append log and update task
        val secondLog =
            TaskLogEntry(
                operator = "agent-1",
                fromStatus = "TODO",
                toStatus = "IN_PROGRESS",
                comment = "Started work",
            )
        val updatedTask = retrieved1.copy(status = "IN_PROGRESS", logs = retrieved1.logs + secondLog)
        val saved = repository.updateTask(updatedTask)
        assertEquals(2, saved.logs.size)
        assertEquals("Started work", saved.logs[1].comment)

        // Verify loaded via getTask
        val retrieved2 = repository.getTask(task.id)
        assertNotNull(retrieved2)
        assertEquals(2, retrieved2.logs.size)
        assertEquals("TODO", retrieved2.logs[1].fromStatus)
        assertEquals("IN_PROGRESS", retrieved2.logs[1].toStatus)

        // Verify loaded via listTasks
        val listed = repository.listTasks(status = "IN_PROGRESS")
        assertEquals(1, listed.size)
        assertEquals(2, listed[0].logs.size)
    }

    @Test
    @DisplayName("Should persist and retrieve branch field on task create and update")
    fun testTaskBranchPersistenceAndCrud() {
        val taskWithBranch =
            Task(
                title = "Feature with dedicated branch",
                status = "TODO",
                branch = "feature/structured-branch-test",
            )
        val created = repository.createTask(taskWithBranch)
        assertNotNull(created)
        assertEquals("feature/structured-branch-test", created.branch)

        // Retrieve and verify
        val retrieved = repository.getTask(created.id)
        assertNotNull(retrieved)
        assertEquals("feature/structured-branch-test", retrieved.branch)

        // Update branch
        val updated = repository.updateTask(retrieved.copy(branch = "feature/updated-branch-test"))
        assertEquals("feature/updated-branch-test", updated.branch)

        val retrievedAfterUpdate = repository.getTask(created.id)
        assertNotNull(retrievedAfterUpdate)
        assertEquals("feature/updated-branch-test", retrievedAfterUpdate.branch)
    }

    @Test
    @DisplayName("Should automatically migrate legacy SQLite database without branch column")
    fun testSqliteSchemaMigrationForBranchColumn() {
        val legacyDbFile = tempDir.resolve("legacy_kanban.db").toFile()
        val jdbcUrl = "jdbc:sqlite:${legacyDbFile.absolutePath}"

        // Create legacy schema without 'branch' column
        java.sql.DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE columns (
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
                    CREATE TABLE tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        status TEXT NOT NULL,
                        priority TEXT NOT NULL,
                        assignee TEXT,
                        tags TEXT NOT NULL,
                        github_repo TEXT,
                        github_issue_url TEXT,
                        github_pr_url TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        completed_at INTEGER,
                        FOREIGN KEY(status) REFERENCES columns(id)
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE task_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        task_id INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        operator TEXT NOT NULL,
                        from_status TEXT,
                        to_status TEXT,
                        comment TEXT NOT NULL,
                        pr_url TEXT,
                        commit_hash TEXT,
                        FOREIGN KEY(task_id) REFERENCES tasks(id)
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO columns (id, name, display_order, color, is_terminal)
                    VALUES ('TODO', 'To Do', 0, '#6B7280', 0);
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO tasks (
                        title, description, status, priority, assignee, tags,
                        created_at, updated_at
                    ) VALUES (
                        'Legacy Task', 'Created before migration', 'TODO', 'MEDIUM', 'alice', '[]',
                        1000, 1000
                    );
                    """.trimIndent(),
                )
            }
        }

        // Open legacy DB with SqliteTaskRepository which should trigger schema migration
        val repo = SqliteTaskRepository(jdbcUrl)
        try {
            // Verify existing legacy task is loaded with branch = null
            val legacyTask = repo.getTask(1)
            assertNotNull(legacyTask)
            assertEquals("Legacy Task", legacyTask.title)
            assertNull(legacyTask.branch)

            // Verify updating legacy task with a branch works
            val updated = repo.updateTask(legacyTask.copy(branch = "feature/migrated-legacy"))
            assertEquals("feature/migrated-legacy", updated.branch)
            assertEquals("feature/migrated-legacy", repo.getTask(1)?.branch)

            // Verify creating a new task with branch works
            val newTask = repo.createTask(Task(title = "New Task After Migration", branch = "feature/new-after-migration"))
            assertEquals("feature/new-after-migration", newTask.branch)
            assertEquals("feature/new-after-migration", repo.getTask(newTask.id)?.branch)
        } finally {
            repo.close()
        }
    }
}
