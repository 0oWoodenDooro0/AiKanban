package aikanban.api

import aikanban.api.dto.AddCommentRequest
import aikanban.api.dto.ClaimTaskRequest
import aikanban.api.dto.CreateColumnRequest
import aikanban.api.dto.CreateTaskRequest
import aikanban.api.dto.ErrorResponse
import aikanban.api.dto.MessageResponse
import aikanban.api.dto.MoveTaskRequest
import aikanban.api.dto.ReleaseTaskRequest
import aikanban.api.dto.UpdateColumnRequest
import aikanban.api.dto.UpdateTaskRequest
import aikanban.model.BoardColumn
import aikanban.model.Task
import aikanban.model.TaskLogEntry
import aikanban.model.TaskPriority
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KanbanApiTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var repository: SqliteTaskRepository
    private lateinit var service: KanbanService

    private val jsonConfig =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
            encodeDefaults = true
        }

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("api_test.db").toFile()
        repository = SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}")
        service = DefaultKanbanService(repository)
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    private fun withKanbanApp(block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit) =
        testApplication {
            application {
                kanbanModule(service)
            }
            val client =
                createClient {
                    install(ContentNegotiation) {
                        json(jsonConfig)
                    }
                }
            block(client)
        }

    // ==========================================
    // 1. Column API Endpoints
    // ==========================================

    @Nested
    @DisplayName("Column API Routes (/api/columns)")
    inner class ColumnApiTests {
        @Test
        @DisplayName("GET /api/columns should return default 7 columns ordered by 'order'")
        fun testGetColumns() =
            withKanbanApp { client ->
                val response = client.get("/api/columns")
                assertEquals(HttpStatusCode.OK, response.status)

                val columns: List<BoardColumn> = response.body()
                assertEquals(7, columns.size)
                assertEquals(listOf("TODO", "IN_PROGRESS", "REVIEW", "REQUEST", "PENDING", "REOPEN", "DONE"), columns.map { it.id })
                assertEquals("To Do", columns[0].name)
                assertTrue(columns.last().isTerminal)
            }

        @Test
        @DisplayName("GET /api/columns/{id} should return column if found or 404")
        fun testGetColumnById() =
            withKanbanApp { client ->
                // Existing column
                val response = client.get("/api/columns/TODO")
                assertEquals(HttpStatusCode.OK, response.status)
                val col: BoardColumn = response.body()
                assertEquals("TODO", col.id)
                assertEquals("To Do", col.name)

                // Non-existent column
                val notFound = client.get("/api/columns/NONEXISTENT")
                assertEquals(HttpStatusCode.NotFound, notFound.status)
                val err: ErrorResponse = notFound.body()
                assertTrue(err.error.contains("not found", ignoreCase = true))
            }

        @Test
        @DisplayName("POST /api/columns should create a new column and return 201 Created")
        fun testCreateColumn() =
            withKanbanApp { client ->
                val req =
                    CreateColumnRequest(
                        id = "BLOCKED",
                        name = "Blocked Issues",
                        order = 3,
                        color = "#EF4444",
                        isTerminal = false,
                    )
                val response =
                    client.post("/api/columns") {
                        contentType(ContentType.Application.Json)
                        setBody(req)
                    }
                assertEquals(HttpStatusCode.Created, response.status)
                val created: BoardColumn = response.body()
                assertEquals("BLOCKED", created.id)
                assertEquals("Blocked Issues", created.name)
                assertEquals(3, created.order)
                assertEquals("#EF4444", created.color)
                assertFalse(created.isTerminal)

                // Verify it appears in GET /api/columns
                val allCols: List<BoardColumn> = client.get("/api/columns").body()
                assertEquals(8, allCols.size)
            }

        @Test
        @DisplayName("POST /api/columns with invalid input should return 400 Bad Request")
        fun testCreateColumnValidation() =
            withKanbanApp { client ->
                // Blank ID
                val badIdReq = CreateColumnRequest(id = "", name = "Invalid", order = 1)
                val res1 =
                    client.post("/api/columns") {
                        contentType(ContentType.Application.Json)
                        setBody(badIdReq)
                    }
                assertEquals(HttpStatusCode.BadRequest, res1.status)

                // Blank Name
                val badNameReq = CreateColumnRequest(id = "TEST", name = "  ", order = 1)
                val res2 =
                    client.post("/api/columns") {
                        contentType(ContentType.Application.Json)
                        setBody(badNameReq)
                    }
                assertEquals(HttpStatusCode.BadRequest, res2.status)

                // Duplicate ID
                val dupReq = CreateColumnRequest(id = "TODO", name = "Duplicate Todo", order = 0)
                val res3 =
                    client.post("/api/columns") {
                        contentType(ContentType.Application.Json)
                        setBody(dupReq)
                    }
                assertEquals(HttpStatusCode.BadRequest, res3.status)
            }

        @Test
        @DisplayName("PUT /api/columns/{id} should update column and return 200 OK")
        fun testUpdateColumn() =
            withKanbanApp { client ->
                val updateReq =
                    UpdateColumnRequest(
                        name = "Needs Review",
                        order = 2,
                        color = "#A855F7",
                        isTerminal = false,
                    )
                val response =
                    client.put("/api/columns/REVIEW") {
                        contentType(ContentType.Application.Json)
                        setBody(updateReq)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val updated: BoardColumn = response.body()
                assertEquals("REVIEW", updated.id)
                assertEquals("Needs Review", updated.name)
                assertEquals("#A855F7", updated.color)

                // Update non-existent column
                val notFound =
                    client.put("/api/columns/UNKNOWN") {
                        contentType(ContentType.Application.Json)
                        setBody(updateReq)
                    }
                assertEquals(HttpStatusCode.NotFound, notFound.status)
            }

        @Test
        @DisplayName("DELETE /api/columns/{id} should delete column or return error if tasks exist")
        fun testDeleteColumn() =
            withKanbanApp { client ->
                // Create a temporary column
                client.post("/api/columns") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateColumnRequest(id = "TEMP", name = "Temporary", order = 10))
                }

                // Delete it
                val deleteRes = client.delete("/api/columns/TEMP")
                assertEquals(HttpStatusCode.OK, deleteRes.status)

                // Try deleting non-existent column
                val notFoundRes = client.delete("/api/columns/TEMP")
                assertEquals(HttpStatusCode.NotFound, notFoundRes.status)

                // Try deleting column with active tasks
                client.post("/api/tasks") {
                    contentType(ContentType.Application.Json)
                    setBody(CreateTaskRequest(title = "Active in TODO", status = "TODO"))
                }
                val failDelete = client.delete("/api/columns/TODO")
                assertEquals(HttpStatusCode.BadRequest, failDelete.status)
            }
    }

    // ==========================================
    // 2. Task CRUD & Query Endpoints
    // ==========================================

    @Nested
    @DisplayName("Task CRUD & Query Routes (/api/tasks)")
    inner class TaskCrudApiTests {
        @Test
        @DisplayName("GET /api/tasks should return empty list initially")
        fun testGetTasksEmpty() =
            withKanbanApp { client ->
                val response = client.get("/api/tasks")
                assertEquals(HttpStatusCode.OK, response.status)
                val tasks: List<Task> = response.body()
                assertTrue(tasks.isEmpty())
            }

        @Test
        @DisplayName("POST /api/tasks should create task with all attributes and return 201 Created")
        fun testCreateTask() =
            withKanbanApp { client ->
                val req =
                    CreateTaskRequest(
                        title = "Implement Ktor server",
                        description = "Setup Netty server with SSE and REST",
                        priority = TaskPriority.HIGH,
                        assignee = "agent-alpha",
                        tags = setOf("backend", "ktor"),
                        githubRepo = "0oWoodenDooro0/AiKanban",
                        githubIssueUrl = "https://github.com/0oWoodenDooro0/AiKanban/issues/4",
                        status = "TODO",
                        operator = "agent-alpha",
                    )
                val response =
                    client.post("/api/tasks") {
                        contentType(ContentType.Application.Json)
                        setBody(req)
                    }
                assertEquals(HttpStatusCode.Created, response.status)

                val created: Task = response.body()
                assertTrue(created.id > 0)
                assertEquals("Implement Ktor server", created.title)
                assertEquals("Setup Netty server with SSE and REST", created.description)
                assertEquals(TaskPriority.HIGH, created.priority)
                assertEquals("agent-alpha", created.assignee)
                assertEquals(setOf("backend", "ktor"), created.tags)
                assertEquals("0oWoodenDooro0/AiKanban", created.githubRepo)
                assertEquals("https://github.com/0oWoodenDooro0/AiKanban/issues/4", created.githubIssueUrl)
                assertEquals("TODO", created.status)
                assertEquals(1, created.logs.size)
                assertEquals("agent-alpha", created.logs[0].operator)
                assertTrue(created.logs[0].comment.contains("created", ignoreCase = true))
            }

        @Test
        @DisplayName("POST /api/tasks should fail with 400 for empty title or invalid column")
        fun testCreateTaskValidation() =
            withKanbanApp { client ->
                // Empty title
                val emptyTitleReq = CreateTaskRequest(title = "   ")
                val res1 =
                    client.post("/api/tasks") {
                        contentType(ContentType.Application.Json)
                        setBody(emptyTitleReq)
                    }
                assertEquals(HttpStatusCode.BadRequest, res1.status)

                // Invalid column status
                val invalidStatusReq = CreateTaskRequest(title = "Valid Title", status = "INVALID_COL")
                val res2 =
                    client.post("/api/tasks") {
                        contentType(ContentType.Application.Json)
                        setBody(invalidStatusReq)
                    }
                assertEquals(HttpStatusCode.NotFound, res2.status)
            }

        @Test
        @DisplayName("GET /api/tasks/{id} should return task or 404/400")
        fun testGetTaskById() =
            withKanbanApp { client ->
                val task = service.createTask(title = "Task 1", priority = TaskPriority.URGENT)

                // Found
                val res = client.get("/api/tasks/${task.id}")
                assertEquals(HttpStatusCode.OK, res.status)
                val fetched: Task = res.body()
                assertEquals(task.id, fetched.id)
                assertEquals("Task 1", fetched.title)

                // Not found
                val notFound = client.get("/api/tasks/99999")
                assertEquals(HttpStatusCode.NotFound, notFound.status)

                // Invalid ID (string)
                val badId = client.get("/api/tasks/abc")
                assertEquals(HttpStatusCode.BadRequest, badId.status)
            }

        @Test
        @DisplayName("GET /api/tasks with filters should filter by status, assignee, tag, and priority")
        fun testGetTasksFiltering() =
            withKanbanApp { client ->
                service.createTask(
                    title = "Task 1",
                    status = "TODO",
                    priority = TaskPriority.LOW,
                    tags = setOf("frontend"),
                )
                service.createTask(
                    title = "Task 2",
                    status = "IN_PROGRESS",
                    assignee = "alice",
                    priority = TaskPriority.HIGH,
                    tags = setOf("backend"),
                )
                service.createTask(
                    title = "Task 3",
                    status = "TODO",
                    assignee = "bob",
                    priority = TaskPriority.HIGH,
                    tags = setOf("backend", "database"),
                )

                // Filter by status
                val todoList: List<Task> = client.get("/api/tasks?status=TODO").body()
                assertEquals(2, todoList.size)

                // Filter by assignee
                val aliceList: List<Task> = client.get("/api/tasks?assignee=alice").body()
                assertEquals(1, aliceList.size)
                assertEquals("Task 2", aliceList[0].title)

                // Filter by tag
                val backendList: List<Task> = client.get("/api/tasks?tag=backend").body()
                assertEquals(2, backendList.size)

                // Filter by priority
                val highList: List<Task> = client.get("/api/tasks?priority=HIGH").body()
                assertEquals(2, highList.size)

                // Combined filter
                val combined: List<Task> = client.get("/api/tasks?status=TODO&priority=HIGH&tag=backend").body()
                assertEquals(1, combined.size)
                assertEquals("Task 3", combined[0].title)
            }

        @Test
        @DisplayName("PUT /api/tasks/{id} should update fields and return 200 OK")
        fun testUpdateTask() =
            withKanbanApp { client ->
                val task = service.createTask(title = "Original Title", description = "Original Desc")

                val updateReq =
                    UpdateTaskRequest(
                        title = "Updated Title",
                        description = "Updated Desc",
                        priority = TaskPriority.URGENT,
                        assignee = "bob",
                        tags = setOf("v2"),
                        githubPrUrl = "https://github.com/pr/1",
                        operator = "reviewer-1",
                        comment = "Updated task metadata",
                    )
                val response =
                    client.put("/api/tasks/${task.id}") {
                        contentType(ContentType.Application.Json)
                        setBody(updateReq)
                    }
                assertEquals(HttpStatusCode.OK, response.status)

                val updated: Task = response.body()
                assertEquals("Updated Title", updated.title)
                assertEquals("Updated Desc", updated.description)
                assertEquals(TaskPriority.URGENT, updated.priority)
                assertEquals("bob", updated.assignee)
                assertEquals(setOf("v2"), updated.tags)
                assertEquals("https://github.com/pr/1", updated.githubPrUrl)

                // Update non-existent task
                val notFound =
                    client.put("/api/tasks/99999") {
                        contentType(ContentType.Application.Json)
                        setBody(updateReq)
                    }
                assertEquals(HttpStatusCode.NotFound, notFound.status)
            }

        @Test
        @DisplayName("DELETE /api/tasks/{id} should delete task or return 404")
        fun testDeleteTask() =
            withKanbanApp { client ->
                val task = service.createTask(title = "Task to delete")
                val res = client.delete("/api/tasks/${task.id}")
                assertEquals(HttpStatusCode.OK, res.status)
                val msg: MessageResponse = res.body()
                assertTrue(msg.message.contains("deleted", ignoreCase = true))

                // Verify task deleted
                val notFound = client.get("/api/tasks/${task.id}")
                assertEquals(HttpStatusCode.NotFound, notFound.status)

                // Delete non-existent task
                val deleteNotFound = client.delete("/api/tasks/${task.id}")
                assertEquals(HttpStatusCode.NotFound, deleteNotFound.status)
            }
    }

    // ==========================================
    // 3. Task Workflow & Action Endpoints
    // ==========================================

    @Nested
    @DisplayName("Task Workflow & Actions (/api/tasks/...)")
    inner class TaskWorkflowApiTests {
        @Test
        @DisplayName("POST /api/tasks/{id}/move should move task status and append audit log")
        fun testMoveTask() =
            withKanbanApp { client ->
                val task = service.createTask(title = "Feature Task", status = "TODO")

                val moveReq =
                    MoveTaskRequest(
                        toStatus = "IN_PROGRESS",
                        operator = "agent-47",
                        comment = "Started working on feature",
                        assignee = "agent-47",
                    )
                val response =
                    client.post("/api/tasks/${task.id}/move") {
                        contentType(ContentType.Application.Json)
                        setBody(moveReq)
                    }
                assertEquals(HttpStatusCode.OK, response.status)

                val moved: Task = response.body()
                assertEquals("IN_PROGRESS", moved.status)
                assertEquals("agent-47", moved.assignee)
                assertEquals(2, moved.logs.size)
                assertEquals("Started working on feature", moved.logs.last().comment)

                // Move to terminal DONE status sets completedAt
                val doneReq = MoveTaskRequest(toStatus = "DONE", operator = "reviewer")
                val doneRes =
                    client.post("/api/tasks/${task.id}/move") {
                        contentType(ContentType.Application.Json)
                        setBody(doneReq)
                    }
                assertEquals(HttpStatusCode.OK, doneRes.status)
                val doneTask: Task = doneRes.body()
                assertEquals("DONE", doneTask.status)
                assertNotNull(doneTask.completedAt)

                // Move to invalid column returns 404
                val invalidColRes =
                    client.post("/api/tasks/${task.id}/move") {
                        contentType(ContentType.Application.Json)
                        setBody(MoveTaskRequest(toStatus = "INVALID"))
                    }
                assertEquals(HttpStatusCode.NotFound, invalidColRes.status)
            }

        @Test
        @DisplayName("POST /api/tasks/claim should claim next available task atomically")
        fun testClaimTask() =
            withKanbanApp { client ->
                service.createTask(title = "Task Low", priority = TaskPriority.LOW, status = "TODO", tags = setOf("ai"))
                service.createTask(title = "Task High", priority = TaskPriority.HIGH, status = "TODO", tags = setOf("ai"))

                val claimReq =
                    ClaimTaskRequest(
                        agentName = "agent-smith",
                        fromStatus = "TODO",
                        toStatus = "IN_PROGRESS",
                        tag = "ai",
                    )
                val response =
                    client.post("/api/tasks/claim") {
                        contentType(ContentType.Application.Json)
                        setBody(claimReq)
                    }
                assertEquals(HttpStatusCode.OK, response.status)

                val claimed: Task = response.body()
                // Should claim highest priority task first ("Task High")
                assertEquals("Task High", claimed.title)
                assertEquals("IN_PROGRESS", claimed.status)
                assertEquals("agent-smith", claimed.assignee)

                // Claim next task
                val res2 =
                    client.post("/api/tasks/claim") {
                        contentType(ContentType.Application.Json)
                        setBody(claimReq)
                    }
                assertEquals(HttpStatusCode.OK, res2.status)
                val claimed2: Task = res2.body()
                assertEquals("Task Low", claimed2.title)

                // No more tasks to claim -> 204 No Content
                val res3 =
                    client.post("/api/tasks/claim") {
                        contentType(ContentType.Application.Json)
                        setBody(claimReq)
                    }
                assertEquals(HttpStatusCode.NoContent, res3.status)
            }

        @Test
        @DisplayName("POST /api/tasks/{id}/release should release task and clear assignee")
        fun testReleaseTask() =
            withKanbanApp { client ->
                val task =
                    service.createTask(
                        title = "Claimed Task",
                        status = "IN_PROGRESS",
                        assignee = "agent-smith",
                    )

                val releaseReq =
                    ReleaseTaskRequest(
                        operator = "agent-smith",
                        targetStatus = "TODO",
                        comment = "Releasing back to pool",
                    )
                val response =
                    client.post("/api/tasks/${task.id}/release") {
                        contentType(ContentType.Application.Json)
                        setBody(releaseReq)
                    }
                assertEquals(HttpStatusCode.OK, response.status)

                val released: Task = response.body()
                assertEquals("TODO", released.status)
                assertNull(released.assignee)
                assertNull(released.completedAt)
            }
    }

    // ==========================================
    // 4. Task Audit Logs Endpoints
    // ==========================================

    @Nested
    @DisplayName("Task Audit Logs (/api/tasks/{id}/logs)")
    inner class TaskLogsApiTests {
        @Test
        @DisplayName("GET /api/tasks/{id}/logs should return list of log entries")
        fun testGetTaskLogs() =
            withKanbanApp { client ->
                val task = service.createTask(title = "Task with logs", operator = "creator")
                service.moveTask(task.id, toStatus = "IN_PROGRESS", operator = "worker", comment = "Working")

                val response = client.get("/api/tasks/${task.id}/logs")
                assertEquals(HttpStatusCode.OK, response.status)

                val logs: List<TaskLogEntry> = response.body()
                assertEquals(2, logs.size)
                assertEquals("creator", logs[0].operator)
                assertEquals("worker", logs[1].operator)
                assertEquals("Working", logs[1].comment)

                // Not found task logs
                val notFound = client.get("/api/tasks/99999/logs")
                assertEquals(HttpStatusCode.NotFound, notFound.status)
            }

        @Test
        @DisplayName("POST /api/tasks/{id}/logs should append audit comment and return 201 Created")
        fun testAddComment() =
            withKanbanApp { client ->
                val task = service.createTask(title = "Task for commenting")

                val commentReq =
                    AddCommentRequest(
                        operator = "agent-reviewer",
                        comment = "PR look good, ready to merge",
                        prUrl = "https://github.com/pr/42",
                        commitHash = "abc1234",
                    )
                val response =
                    client.post("/api/tasks/${task.id}/logs") {
                        contentType(ContentType.Application.Json)
                        setBody(commentReq)
                    }
                assertEquals(HttpStatusCode.Created, response.status)

                val entry: TaskLogEntry = response.body()
                assertEquals("agent-reviewer", entry.operator)
                assertEquals("PR look good, ready to merge", entry.comment)
                assertEquals("https://github.com/pr/42", entry.prUrl)
                assertEquals("abc1234", entry.commitHash)

                // Verify log is in task logs
                val taskLogs = service.getTaskLogs(task.id)
                assertEquals(2, taskLogs.size)
                assertEquals("agent-reviewer", taskLogs.last().operator)

                // Comment on non-existent task
                val notFound =
                    client.post("/api/tasks/99999/logs") {
                        contentType(ContentType.Application.Json)
                        setBody(commentReq)
                    }
                assertEquals(HttpStatusCode.NotFound, notFound.status)
            }
    }

    // ==========================================
    // 5. CORS and Content Negotiation Headers
    // ==========================================

    @Nested
    @DisplayName("CORS and Content-Type Headers")
    inner class HeadersAndCorsTests {
        @Test
        @DisplayName("OPTIONS preflight requests should return CORS headers")
        fun testCorsPreflight() =
            withKanbanApp { client ->
                val response =
                    client.options("/api/tasks") {
                        header(HttpHeaders.Origin, "http://localhost:3000")
                        header(HttpHeaders.AccessControlRequestMethod, "POST")
                        header(HttpHeaders.AccessControlRequestHeaders, "Content-Type")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertNotNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
            }
    }
}
