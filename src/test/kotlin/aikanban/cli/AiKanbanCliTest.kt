package aikanban.cli

import aikanban.model.BoardColumn
import aikanban.model.Task
import aikanban.model.TaskLogEntry
import aikanban.model.TaskPriority
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiKanbanCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var repository: SqliteTaskRepository
    private lateinit var service: KanbanService
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("cli_test.db").toFile()
        repository = SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}")
        service = DefaultKanbanService(repository)
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    private data class CliExecutionResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun execute(vararg args: String): CliExecutionResult {
        val outStream = ByteArrayOutputStream()
        val errStream = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        val printOut = PrintStream(outStream, true, StandardCharsets.UTF_8)
        val printErr = PrintStream(errStream, true, StandardCharsets.UTF_8)

        System.setOut(printOut)
        System.setErr(printErr)

        try {
            val command = AiKanbanCommand(serviceOverride = service)
            val exitCode = command.parseArgs(args.toList())
            return CliExecutionResult(
                exitCode = exitCode,
                stdout = outStream.toString(StandardCharsets.UTF_8).trim(),
                stderr = errStream.toString(StandardCharsets.UTF_8).trim(),
            )
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    // ==========================================
    // 1. Root Command & Help Tests
    // ==========================================

    @Nested
    @DisplayName("Root Command & Help")
    inner class RootCommandTests {
        @Test
        @DisplayName("Should display help message with all available subcommands")
        fun testRootHelp() {
            val result = execute("--help")
            assertTrue(result.stdout.contains("aikanban") || result.stdout.contains("Usage:"))
            assertTrue(result.stdout.contains("list"))
            assertTrue(result.stdout.contains("add"))
            assertTrue(result.stdout.contains("show"))
            assertTrue(result.stdout.contains("move"))
            assertTrue(result.stdout.contains("claim"))
            assertTrue(result.stdout.contains("log"))
            assertTrue(result.stdout.contains("update"))
            assertTrue(result.stdout.contains("column"))
        }
    }

    // ==========================================
    // 2. Add Command Tests
    // ==========================================

    @Nested
    @DisplayName("Add Command")
    inner class AddCommandTests {
        @Test
        @DisplayName("Should create task in human mode and output task details")
        fun testAddTaskHuman() {
            val result =
                execute(
                    "add",
                    "Implement Login Feature",
                    "-d",
                    "Support OAuth2 login",
                    "-p",
                    "HIGH",
                    "-a",
                    "Alice",
                    "-t",
                    "auth,backend",
                    "-s",
                    "TODO",
                    "--repo",
                    "org/repo",
                    "--issue",
                    "https://github.com/org/repo/issues/10",
                    "-o",
                    "admin",
                )
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Implement Login Feature"))
            assertTrue(result.stdout.contains("1") || result.stdout.contains("Created"))

            val task = service.getTask(1)
            assertEquals("Implement Login Feature", task.title)
            assertEquals("Support OAuth2 login", task.description)
            assertEquals(TaskPriority.HIGH, task.priority)
            assertEquals("Alice", task.assignee)
            assertEquals(setOf("auth", "backend"), task.tags)
            assertEquals("TODO", task.status)
            assertEquals("org/repo", task.githubRepo)
            assertEquals("https://github.com/org/repo/issues/10", task.githubIssueUrl)
        }

        @Test
        @DisplayName("Should create task in JSON mode and output parseable Task JSON")
        fun testAddTaskJson() {
            val result =
                execute(
                    "add",
                    "Agent Task",
                    "-p",
                    "URGENT",
                    "-t",
                    "ai,agent",
                    "--json",
                )
            assertEquals(0, result.exitCode)
            val task = json.decodeFromString<Task>(result.stdout)
            assertEquals(1, task.id)
            assertEquals("Agent Task", task.title)
            assertEquals(TaskPriority.URGENT, task.priority)
            assertEquals(setOf("ai", "agent"), task.tags)
            assertEquals("TODO", task.status)
        }

        @Test
        @DisplayName("Should inherit global --json flag")
        fun testAddTaskGlobalJson() {
            val result =
                execute(
                    "--json",
                    "add",
                    "Global Json Task",
                )
            assertEquals(0, result.exitCode)
            val task = json.decodeFromString<Task>(result.stdout)
            assertEquals("Global Json Task", task.title)
        }
    }

    // ==========================================
    // 3. List Command Tests
    // ==========================================

    @Nested
    @DisplayName("List Command")
    inner class ListCommandTests {
        @BeforeEach
        fun populateTasks() {
            service.createTask(title = "Task 1", priority = TaskPriority.LOW, assignee = "Alice", tags = setOf("frontend"), status = "TODO")
            service.createTask(
                title = "Task 2",
                priority = TaskPriority.HIGH,
                assignee = "Bob",
                tags = setOf("backend"),
                status = "IN_PROGRESS",
            )
            service.createTask(
                title = "Task 3",
                priority = TaskPriority.URGENT,
                assignee = "Alice",
                tags = setOf("backend", "urgent"),
                status = "DONE",
            )
        }

        @Test
        @DisplayName("Should list all tasks in human table format")
        fun testListTasksHuman() {
            val result = execute("list")
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Task 1"))
            assertTrue(result.stdout.contains("Task 2"))
            assertTrue(result.stdout.contains("Task 3"))
        }

        @Test
        @DisplayName("Should list all tasks in JSON format")
        fun testListTasksJson() {
            val result = execute("list", "--json")
            assertEquals(0, result.exitCode)
            val tasks = json.decodeFromString<List<Task>>(result.stdout)
            assertEquals(3, tasks.size)
            assertEquals("Task 1", tasks[0].title)
            assertEquals("Task 2", tasks[1].title)
            assertEquals("Task 3", tasks[2].title)
        }

        @Test
        @DisplayName("Should filter tasks by status, assignee, tag, and priority")
        fun testListTasksFilters() {
            // Filter by status
            val statusResult = execute("list", "-s", "IN_PROGRESS", "--json")
            val statusTasks = json.decodeFromString<List<Task>>(statusResult.stdout)
            assertEquals(1, statusTasks.size)
            assertEquals("Task 2", statusTasks[0].title)

            // Filter by assignee
            val assigneeResult = execute("list", "-a", "Alice", "--json")
            val assigneeTasks = json.decodeFromString<List<Task>>(assigneeResult.stdout)
            assertEquals(2, assigneeTasks.size)

            // Filter by tag
            val tagResult = execute("list", "-t", "backend", "--json")
            val tagTasks = json.decodeFromString<List<Task>>(tagResult.stdout)
            assertEquals(2, tagTasks.size)

            // Filter by priority
            val priorityResult = execute("list", "-p", "URGENT", "--json")
            val priorityTasks = json.decodeFromString<List<Task>>(priorityResult.stdout)
            assertEquals(1, priorityTasks.size)
            assertEquals("Task 3", priorityTasks[0].title)
        }

        @Test
        @DisplayName("Should render empty state message when no tasks match")
        fun testListEmptyState() {
            val result = execute("list", "-s", "REOPEN")
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("No tasks found") || result.stdout.contains("Empty") || result.stdout.isNotBlank())
        }
    }

    // ==========================================
    // 4. Show Command Tests
    // ==========================================

    @Nested
    @DisplayName("Show Command")
    inner class ShowCommandTests {
        @Test
        @DisplayName("Should display full task card with markdown description and logs in human mode")
        fun testShowTaskHuman() {
            val task =
                service.createTask(
                    title = "Markdown Task",
                    description = "## Feature Overview\n- Item 1\n- Item 2",
                    priority = TaskPriority.HIGH,
                    assignee = "Alice",
                    tags = setOf("feature"),
                    githubRepo = "myorg/repo",
                )
            service.addComment(task.id, operator = "Bob", comment = "Work started on this feature")

            val result = execute("show", task.id.toString())
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Markdown Task"))
            assertTrue(result.stdout.contains("Feature Overview") || result.stdout.contains("Item 1"))
            assertTrue(result.stdout.contains("Bob"))
            assertTrue(result.stdout.contains("Work started on this feature"))
        }

        @Test
        @DisplayName("Should return full Task JSON in JSON mode")
        fun testShowTaskJson() {
            val created = service.createTask(title = "Show JSON Task", priority = TaskPriority.MEDIUM)
            service.addComment(created.id, operator = "Alice", comment = "First comment")

            val result = execute("show", created.id.toString(), "--json")
            assertEquals(0, result.exitCode)
            val task = json.decodeFromString<Task>(result.stdout)
            assertEquals(created.id, task.id)
            assertEquals("Show JSON Task", task.title)
            assertEquals(2, task.logs.size)
            assertEquals("First comment", task.logs.last().comment)
        }

        @Test
        @DisplayName("Should handle not found task gracefully")
        fun testShowTaskNotFound() {
            val result = execute("show", "999")
            val notFoundInHuman =
                result.exitCode != 0 ||
                    result.stderr.contains("not found", ignoreCase = true) ||
                    result.stdout.contains("not found", ignoreCase = true)
            assertTrue(notFoundInHuman)

            val jsonResult = execute("show", "999", "--json")
            val errorInJson =
                jsonResult.exitCode != 0 ||
                    jsonResult.stdout.contains("error", ignoreCase = true) ||
                    jsonResult.stderr.contains("error", ignoreCase = true)
            assertTrue(errorInJson)
        }
    }

    // ==========================================
    // 5. Move Command Tests
    // ==========================================

    @Nested
    @DisplayName("Move Command")
    inner class MoveCommandTests {
        @Test
        @DisplayName("Should transition task status and record audit log in human mode")
        fun testMoveTaskHuman() {
            val task = service.createTask(title = "Move Task", status = "TODO")
            val result =
                execute(
                    "move",
                    task.id.toString(),
                    "IN_PROGRESS",
                    "-o",
                    "Alice",
                    "-c",
                    "Starting implementation",
                    "-a",
                    "Alice",
                    "--pr",
                    "https://github.com/org/repo/pull/1",
                )
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("IN_PROGRESS"))

            val updated = service.getTask(task.id)
            assertEquals("IN_PROGRESS", updated.status)
            assertEquals("Alice", updated.assignee)
            assertEquals("https://github.com/org/repo/pull/1", updated.githubPrUrl)
            assertEquals(2, updated.logs.size)
            assertEquals("Starting implementation", updated.logs.last().comment)
        }

        @Test
        @DisplayName("Should transition task and return updated Task in JSON mode")
        fun testMoveTaskJson() {
            val task = service.createTask(title = "Move JSON Task", status = "TODO")
            val result =
                execute(
                    "move",
                    task.id.toString(),
                    "DONE",
                    "-o",
                    "Bob",
                    "-c",
                    "Completed",
                    "--json",
                )
            assertEquals(0, result.exitCode)
            val updated = json.decodeFromString<Task>(result.stdout)
            assertEquals("DONE", updated.status)
            assertNotNull(updated.completedAt)
        }
    }

    // ==========================================
    // 6. Claim Command Tests
    // ==========================================

    @Nested
    @DisplayName("Claim Command")
    inner class ClaimCommandTests {
        @Test
        @DisplayName("Should claim highest priority task for agent in human mode")
        fun testClaimTaskHuman() {
            service.createTask(title = "Low Task", priority = TaskPriority.LOW, status = "TODO")
            val urgent = service.createTask(title = "Urgent Task", priority = TaskPriority.URGENT, status = "TODO")

            val result = execute("claim", "Agent-Smith")
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Agent-Smith"))
            assertTrue(result.stdout.contains("Urgent Task"))

            val updated = service.getTask(urgent.id)
            assertEquals("IN_PROGRESS", updated.status)
            assertEquals("Agent-Smith", updated.assignee)
        }

        @Test
        @DisplayName("Should claim task matching tag filter in JSON mode")
        fun testClaimTaskWithTagJson() {
            service.createTask(title = "Frontend Task", priority = TaskPriority.HIGH, tags = setOf("frontend"), status = "TODO")
            val backendTask =
                service.createTask(
                    title = "Backend Task",
                    priority = TaskPriority.MEDIUM,
                    tags = setOf("backend"),
                    status = "TODO",
                )

            val result = execute("claim", "Backend-Bot", "--tag", "backend", "--json")
            assertEquals(0, result.exitCode)
            val claimed = json.decodeFromString<Task>(result.stdout)
            assertEquals(backendTask.id, claimed.id)
            assertEquals("Backend-Bot", claimed.assignee)
            assertEquals("IN_PROGRESS", claimed.status)
        }

        @Test
        @DisplayName("Should handle no available tasks gracefully")
        fun testClaimWhenNoTasksAvailable() {
            val result = execute("claim", "Agent-Zero")
            assertEquals(0, result.exitCode)
            val humanMessage =
                result.stdout.contains("No available tasks") ||
                    result.stdout.contains("None") ||
                    result.stdout.contains("No tasks")
            assertTrue(humanMessage)

            val jsonResult = execute("claim", "Agent-Zero", "--json")
            assertEquals(0, jsonResult.exitCode)
            val jsonValid =
                jsonResult.stdout == "null" ||
                    jsonResult.stdout == "{}" ||
                    jsonResult.stdout.contains("\"task\": null") ||
                    jsonResult.stdout.contains("\"task\":null")
            assertTrue(jsonValid)
        }
    }

    // ==========================================
    // 7. Log Command Tests
    // ==========================================

    @Nested
    @DisplayName("Log Command")
    inner class LogCommandTests {
        @Test
        @DisplayName("Should add comment to task when comment option is provided")
        fun testAddLogComment() {
            val task = service.createTask(title = "Log Test Task")
            val result =
                execute(
                    "log",
                    task.id.toString(),
                    "-m",
                    "Reviewed PR changes",
                    "-o",
                    "Reviewer-1",
                    "--commit",
                    "abcdef1",
                    "--pr",
                    "https://github.com/org/repo/pull/5",
                    "--json",
                )
            assertEquals(0, result.exitCode)
            val logEntry = json.decodeFromString<TaskLogEntry>(result.stdout)
            assertEquals("Reviewed PR changes", logEntry.comment)
            assertEquals("Reviewer-1", logEntry.operator)
            assertEquals("abcdef1", logEntry.commitHash)
            assertEquals("https://github.com/org/repo/pull/5", logEntry.prUrl)
        }

        @Test
        @DisplayName("Should list task audit history when no comment option is passed")
        fun testViewTaskLogs() {
            val task = service.createTask(title = "Audit History Task")
            service.addComment(task.id, operator = "Alice", comment = "First entry")
            service.addComment(task.id, operator = "Bob", comment = "Second entry")

            val humanResult = execute("log", task.id.toString())
            assertEquals(0, humanResult.exitCode)
            assertTrue(humanResult.stdout.contains("First entry"))
            assertTrue(humanResult.stdout.contains("Second entry"))

            val jsonResult = execute("log", task.id.toString(), "--json")
            assertEquals(0, jsonResult.exitCode)
            val logs = json.decodeFromString<List<TaskLogEntry>>(jsonResult.stdout)
            assertEquals(3, logs.size)
            assertEquals("Task created in column TODO", logs[0].comment)
            assertEquals("First entry", logs[1].comment)
            assertEquals("Second entry", logs[2].comment)
        }
    }

    // ==========================================
    // 8. Update Command Tests
    // ==========================================

    @Nested
    @DisplayName("Update Command")
    inner class UpdateCommandTests {
        @Test
        @DisplayName("Should update task properties and return updated task")
        fun testUpdateTask() {
            val task = service.createTask(title = "Initial Title", description = "Old Desc", priority = TaskPriority.LOW)
            val result =
                execute(
                    "update",
                    task.id.toString(),
                    "--title",
                    "Updated Title",
                    "-d",
                    "Updated Description",
                    "-p",
                    "URGENT",
                    "-a",
                    "David",
                    "-t",
                    "refactor,core",
                    "--repo",
                    "myorg/newrepo",
                    "--issue",
                    "https://github.com/myorg/newrepo/issues/42",
                    "--pr",
                    "https://github.com/myorg/newrepo/pull/42",
                    "-o",
                    "Admin",
                    "-c",
                    "Comprehensive update",
                    "--json",
                )
            assertEquals(0, result.exitCode)
            val updated = json.decodeFromString<Task>(result.stdout)
            assertEquals("Updated Title", updated.title)
            assertEquals("Updated Description", updated.description)
            assertEquals(TaskPriority.URGENT, updated.priority)
            assertEquals("David", updated.assignee)
            assertEquals(setOf("refactor", "core"), updated.tags)
            assertEquals("myorg/newrepo", updated.githubRepo)
            assertEquals("https://github.com/myorg/newrepo/issues/42", updated.githubIssueUrl)
            assertEquals("https://github.com/myorg/newrepo/pull/42", updated.githubPrUrl)
            assertEquals(2, updated.logs.size)
            assertEquals("Comprehensive update", updated.logs.last().comment)
        }
    }

    // ==========================================
    // 9. Column Command Tests
    // ==========================================

    @Nested
    @DisplayName("Column Commands")
    inner class ColumnCommandTests {
        @Test
        @DisplayName("Should list all columns in order in human and JSON modes")
        fun testColumnList() {
            val humanResult = execute("column", "list")
            assertEquals(0, humanResult.exitCode)
            assertTrue(humanResult.stdout.contains("TODO"))
            assertTrue(humanResult.stdout.contains("IN_PROGRESS"))
            assertTrue(humanResult.stdout.contains("DONE"))

            val jsonResult = execute("column", "list", "--json")
            assertEquals(0, jsonResult.exitCode)
            val columns = json.decodeFromString<List<BoardColumn>>(jsonResult.stdout)
            assertEquals(7, columns.size)
            assertEquals("TODO", columns[0].id)
            assertEquals("DONE", columns.last().id)
        }

        @Test
        @DisplayName("Should add a new board column")
        fun testColumnAdd() {
            val result =
                execute(
                    "column",
                    "add",
                    "QA",
                    "Quality Assurance",
                    "-o",
                    "5",
                    "-c",
                    "#8B5CF6",
                    "--json",
                )
            assertEquals(0, result.exitCode)
            val created = json.decodeFromString<BoardColumn>(result.stdout)
            assertEquals("QA", created.id)
            assertEquals("Quality Assurance", created.name)
            assertEquals(5, created.order)
            assertEquals("#8B5CF6", created.color)

            val column = service.getColumn("QA")
            assertNotNull(column)
            assertEquals("Quality Assurance", column.name)
        }

        @Test
        @DisplayName("Should update an existing column")
        fun testColumnUpdate() {
            val result =
                execute(
                    "column",
                    "update",
                    "TODO",
                    "-n",
                    "Backlog",
                    "-c",
                    "#475569",
                    "--json",
                )
            assertEquals(0, result.exitCode)
            val updated = json.decodeFromString<BoardColumn>(result.stdout)
            assertEquals("TODO", updated.id)
            assertEquals("Backlog", updated.name)
            assertEquals("#475569", updated.color)

            val column = service.getColumn("TODO")
            assertNotNull(column)
            assertEquals("Backlog", column.name)
        }

        @Test
        @DisplayName("Should delete a non-terminal column")
        fun testColumnDelete() {
            service.createColumn(BoardColumn("CUSTOM", "Custom Column", 10))
            val result =
                execute(
                    "column",
                    "delete",
                    "CUSTOM",
                    "--json",
                )
            assertEquals(0, result.exitCode)
            assertNull(service.getColumn("CUSTOM"))
        }
    }
}
