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
    private var gitHubSyncService: aikanban.github.service.GitHubSyncService? = null
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("cli_test.db").toFile()
        repository = SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}")
        service = DefaultKanbanService(repository)
        gitHubSyncService = null
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
            val command =
                AiKanbanCommand(
                    serviceOverride = service,
                    gitHubSyncServiceOverride = gitHubSyncService,
                )
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
            assertTrue(result.stdout.contains("sync"))
            assertTrue(result.stdout.contains("--generate-completion"))
        }

        @Test
        @DisplayName("Should generate shell completion script for bash, zsh, and fish")
        fun testGenerateCompletion() {
            val bashResult = execute("--generate-completion=bash")
            assertEquals(0, bashResult.exitCode)
            assertTrue(bashResult.stdout.contains("_aikanban") || bashResult.stdout.contains("COMPREPLY"))

            val zshResult = execute("--generate-completion=zsh")
            assertEquals(0, zshResult.exitCode)
            assertTrue(zshResult.stdout.contains("#compdef aikanban") || zshResult.stdout.contains("_aikanban"))

            val fishResult = execute("--generate-completion=fish")
            assertEquals(0, fishResult.exitCode)
            assertTrue(fishResult.stdout.contains("complete -c aikanban"))
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
        @DisplayName("Should list active tasks excluding DONE and sorted by priority DESC and ID ASC by default in human format")
        fun testListTasksHumanDefault() {
            val result = execute("list")
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Task 1"))
            assertTrue(result.stdout.contains("Task 2"))
            assertTrue(!result.stdout.contains("Task 3"), "Default list should exclude DONE tasks")
        }

        @Test
        @DisplayName("Should list active tasks excluding DONE and sorted by priority DESC and ID ASC by default in JSON format")
        fun testListTasksJsonDefault() {
            val result = execute("list", "--json")
            assertEquals(0, result.exitCode)
            val tasks = json.decodeFromString<List<Task>>(result.stdout)
            assertEquals(2, tasks.size)
            // Task 2 is HIGH (level 3), Task 1 is LOW (level 1)
            assertEquals(2, tasks[0].id)
            assertEquals("Task 2", tasks[0].title)
            assertEquals(1, tasks[1].id)
            assertEquals("Task 1", tasks[1].title)
        }

        @Test
        @DisplayName("Should list all tasks including DONE when --all flag is provided")
        fun testListTasksWithAllFlag() {
            val result = execute("list", "--all", "--json")
            assertEquals(0, result.exitCode)
            val tasks = json.decodeFromString<List<Task>>(result.stdout)
            assertEquals(3, tasks.size)
            // Order by priority: Task 3 (URGENT: 4) > Task 2 (HIGH: 3) > Task 1 (LOW: 1)
            assertEquals(3, tasks[0].id)
            assertEquals("Task 3", tasks[0].title)
            assertEquals(2, tasks[1].id)
            assertEquals("Task 2", tasks[1].title)
            assertEquals(1, tasks[2].id)
            assertEquals("Task 1", tasks[2].title)

            val humanResult = execute("list", "--all")
            assertEquals(0, humanResult.exitCode)
            assertTrue(humanResult.stdout.contains("Task 1"))
            assertTrue(humanResult.stdout.contains("Task 2"))
            assertTrue(humanResult.stdout.contains("Task 3"))
        }

        @Test
        @DisplayName("Should list DONE tasks when -s DONE is explicitly provided")
        fun testListTasksExplicitStatusDone() {
            val result = execute("list", "-s", "DONE", "--json")
            assertEquals(0, result.exitCode)
            val tasks = json.decodeFromString<List<Task>>(result.stdout)
            assertEquals(1, tasks.size)
            assertEquals(3, tasks[0].id)
            assertEquals("Task 3", tasks[0].title)
            assertEquals("DONE", tasks[0].status)
        }

        @Test
        @DisplayName("Should strictly sort tasks by priority descending then ID ascending across multiple tasks")
        fun testListTasksPriorityAndIdSortingMultiTasks() {
            service.createTask(title = "Task 4", priority = TaskPriority.HIGH, status = "TODO")
            service.createTask(title = "Task 5", priority = TaskPriority.MEDIUM, status = "REVIEW")
            service.createTask(title = "Task 6", priority = TaskPriority.LOW, status = "TODO")

            val result = execute("list", "--json")
            assertEquals(0, result.exitCode)
            val tasks = json.decodeFromString<List<Task>>(result.stdout)
            assertEquals(5, tasks.size) // Task 3 (DONE) excluded

            // Expected order:
            // 1. Task 2 (HIGH, id 2)
            // 2. Task 4 (HIGH, id 4)
            // 3. Task 5 (MEDIUM, id 5)
            // 4. Task 1 (LOW, id 1)
            // 5. Task 6 (LOW, id 6)
            val taskIds = tasks.map { it.id }
            assertEquals(listOf(2, 4, 5, 1, 6), taskIds)
        }

        @Test
        @DisplayName("Should filter tasks by status, assignee, tag, and priority with default DONE exclusion")
        fun testListTasksFilters() {
            // Filter by status (explicit status retains matched tasks including DONE if requested)
            val statusResult = execute("list", "-s", "IN_PROGRESS", "--json")
            val statusTasks = json.decodeFromString<List<Task>>(statusResult.stdout)
            assertEquals(1, statusTasks.size)
            assertEquals("Task 2", statusTasks[0].title)

            // Filter by assignee: Alice has Task 1 (TODO) and Task 3 (DONE) -> default excludes Task 3
            val assigneeResult = execute("list", "-a", "Alice", "--json")
            val assigneeTasks = json.decodeFromString<List<Task>>(assigneeResult.stdout)
            assertEquals(1, assigneeTasks.size)
            assertEquals("Task 1", assigneeTasks[0].title)

            // Filter by assignee with --all -> includes Task 3 and Task 1
            val assigneeAllResult = execute("list", "-a", "Alice", "--all", "--json")
            val assigneeAllTasks = json.decodeFromString<List<Task>>(assigneeAllResult.stdout)
            assertEquals(2, assigneeAllTasks.size)
            assertEquals(listOf(3, 1), assigneeAllTasks.map { it.id })

            // Filter by tag: backend has Task 2 (IN_PROGRESS) and Task 3 (DONE) -> default excludes Task 3
            val tagResult = execute("list", "-t", "backend", "--json")
            val tagTasks = json.decodeFromString<List<Task>>(tagResult.stdout)
            assertEquals(1, tagTasks.size)
            assertEquals("Task 2", tagTasks[0].title)

            // Filter by tag with --all -> includes Task 3 and Task 2
            val tagAllResult = execute("list", "-t", "backend", "--all", "--json")
            val tagAllTasks = json.decodeFromString<List<Task>>(tagAllResult.stdout)
            assertEquals(2, tagAllTasks.size)
            assertEquals(listOf(3, 2), tagAllTasks.map { it.id })

            // Filter by priority: URGENT is only Task 3 (DONE) -> default excludes Task 3
            val priorityResult = execute("list", "-p", "URGENT", "--json")
            val priorityTasks = json.decodeFromString<List<Task>>(priorityResult.stdout)
            assertEquals(0, priorityTasks.size)

            // Filter by priority with --all -> returns Task 3
            val priorityAllResult = execute("list", "-p", "URGENT", "--all", "--json")
            val priorityAllTasks = json.decodeFromString<List<Task>>(priorityAllResult.stdout)
            assertEquals(1, priorityAllTasks.size)
            assertEquals("Task 3", priorityAllTasks[0].title)
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

    private class TestGitHubClient : aikanban.github.client.GitHubClient {
        val issues = mutableListOf<aikanban.github.model.GitHubIssueDto>()

        override suspend fun fetchRepositoryIssues(
            owner: String,
            repo: String,
            state: String,
            labels: Set<String>,
            token: String?,
            page: Int,
            perPage: Int,
        ): List<aikanban.github.model.GitHubIssueDto> = issues

        override suspend fun fetchIssue(
            owner: String,
            repo: String,
            number: Int,
            token: String?,
        ): aikanban.github.model.GitHubIssueDto? = issues.find { it.number == number }

        override fun close() {}
    }

    // ==========================================
    // 10. Sync Command Tests
    // ==========================================

    @Nested
    @DisplayName("Sync Command")
    inner class SyncCommandTests {
        @Test
        @DisplayName("Should sync issues from repository in human format")
        fun testSyncGitHubHuman() {
            val client = TestGitHubClient()
            client.issues.add(
                aikanban.github.model.GitHubIssueDto(
                    id = 1,
                    number = 101,
                    title = "CLI Synced Issue",
                    body = "Description for CLI issue",
                    state = "open",
                    htmlUrl = "https://github.com/myorg/myrepo/issues/101",
                    labels = listOf(aikanban.github.model.GitHubLabelDto(name = "priority:high")),
                ),
            )
            gitHubSyncService = aikanban.github.service.DefaultGitHubSyncService(service, client)

            val result = execute("sync", "myorg/myrepo", "--provider", "github")
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("CLI Synced Issue") || result.stdout.contains("Synced"))

            val task = service.listTasks().firstOrNull()
            assertNotNull(task)
            assertEquals("CLI Synced Issue", task.title)
        }

        @Test
        @DisplayName("Should sync issues from repository in JSON format")
        fun testSyncGitHubJson() {
            val client = TestGitHubClient()
            client.issues.add(
                aikanban.github.model.GitHubIssueDto(
                    id = 2,
                    number = 102,
                    title = "JSON Synced Issue",
                    state = "open",
                    htmlUrl = "https://github.com/myorg/myrepo/issues/102",
                ),
            )
            gitHubSyncService = aikanban.github.service.DefaultGitHubSyncService(service, client)

            val result = execute("sync", "myorg/myrepo", "--provider", "github", "--json")
            assertEquals(0, result.exitCode)
            val syncResult = json.decodeFromString<aikanban.provider.ProviderSyncResult>(result.stdout)
            assertEquals("myorg/myrepo", syncResult.repo)
            assertEquals(1, syncResult.createdCount)
            assertEquals(1, syncResult.tasks.size)
        }

        @Test
        @DisplayName("Should sync single URL and support --dry-run flag")
        fun testSyncGitHubDryRunAndUrl() {
            val client = TestGitHubClient()
            client.issues.add(
                aikanban.github.model.GitHubIssueDto(
                    id = 3,
                    number = 50,
                    title = "Single URL Dry Run Issue",
                    state = "open",
                    htmlUrl = "https://github.com/myorg/myrepo/issues/50",
                ),
            )
            gitHubSyncService = aikanban.github.service.DefaultGitHubSyncService(service, client)

            val result = execute("sync", "--url", "https://github.com/myorg/myrepo/issues/50", "--dry-run", "--json")
            assertEquals(0, result.exitCode)
            val syncResult = json.decodeFromString<aikanban.provider.ProviderSyncResult>(result.stdout)
            assertEquals(1, syncResult.totalFetched)
            assertEquals(1, syncResult.createdCount)
            assertEquals(0, service.listTasks().size) // dry run: 0 in DB
        }
    }
}
