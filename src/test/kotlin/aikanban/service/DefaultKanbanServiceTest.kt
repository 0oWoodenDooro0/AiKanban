package aikanban.service

import aikanban.model.BoardColumn
import aikanban.model.TaskPriority
import aikanban.repository.SqliteTaskRepository
import aikanban.service.event.KanbanEvent
import aikanban.service.exception.ColumnNotFoundException
import aikanban.service.exception.ColumnValidationException
import aikanban.service.exception.TaskNotFoundException
import aikanban.service.exception.TaskValidationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultKanbanServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var repository: SqliteTaskRepository
    private lateinit var service: KanbanService

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("service_test.db").toFile()
        repository = SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}")
        service = DefaultKanbanService(repository)
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    // ==========================================
    // 1. Board Column Management Tests
    // ==========================================

    @Test
    @DisplayName("Should return the 7 default columns with correct attributes and order")
    fun testDefaultColumns() {
        val columns = service.getColumns()
        assertEquals(7, columns.size)
        assertEquals(listOf("TODO", "IN_PROGRESS", "REVIEW", "REQUEST", "PENDING", "REOPEN", "DONE"), columns.map { it.id })

        val todo = service.getColumn("TODO")
        assertNotNull(todo)
        assertEquals("To Do", todo.name)
        assertEquals(0, todo.order)
        assertFalse(todo.isTerminal)

        val review = service.getColumn("REVIEW")
        assertNotNull(review)
        assertEquals("Review", review.name)
        assertEquals(2, review.order)

        val pending = service.getColumn("PENDING")
        assertNotNull(pending)
        assertEquals("Pending", pending.name)
        assertEquals(4, pending.order)

        val reopen = service.getColumn("REOPEN")
        assertNotNull(reopen)
        assertEquals("Reopened", reopen.name)
        assertEquals(5, reopen.order)

        val done = service.getColumn("DONE")
        assertNotNull(done)
        assertEquals("Done", done.name)
        assertEquals(6, done.order)
        assertTrue(done.isTerminal)
    }

    @Test
    @DisplayName("Should create, update, and delete custom columns")
    fun testCustomColumnManagement() {
        val customCol = BoardColumn(id = "ARCHIVE", name = "Archived", order = 7, color = "#475569", isTerminal = true)
        val created = service.createColumn(customCol)
        assertEquals("ARCHIVE", created.id)
        assertEquals("Archived", created.name)
        assertEquals(8, service.getColumns().size)

        // Update column
        val updatedCol = customCol.copy(name = "Archived Tasks", color = "#334155")
        val updated = service.updateColumn(updatedCol)
        assertEquals("Archived Tasks", updated.name)
        assertEquals("#334155", updated.color)

        // Delete column
        val deleted = service.deleteColumn("ARCHIVE")
        assertTrue(deleted)
        assertNull(service.getColumn("ARCHIVE"))
        assertEquals(7, service.getColumns().size)
    }

    @Test
    @DisplayName("Should validate column creation and deletion constraints")
    fun testColumnValidation() {
        // Blank ID
        assertFailsWith<ColumnValidationException> {
            service.createColumn(BoardColumn(id = "", name = "Invalid", order = 10))
        }

        // Blank Name
        assertFailsWith<ColumnValidationException> {
            service.createColumn(BoardColumn(id = "TEST", name = "  ", order = 10))
        }

        // Duplicate Column ID
        assertFailsWith<ColumnValidationException> {
            service.createColumn(BoardColumn(id = "TODO", name = "Duplicate Todo", order = 10))
        }

        // Update non-existent column
        assertFailsWith<ColumnNotFoundException> {
            service.updateColumn(BoardColumn(id = "NON_EXISTENT", name = "Test", order = 10))
        }

        // Delete non-existent column returns false
        assertFalse(service.deleteColumn("NON_EXISTENT"))

        // Cannot delete column if tasks are present in it
        service.createTask(title = "Task in TODO", status = "TODO")
        assertFailsWith<ColumnValidationException> {
            service.deleteColumn("TODO")
        }
    }

    // ==========================================
    // 2. Task CRUD & Validation Tests
    // ==========================================

    @Test
    @DisplayName("Should create task with full attributes and initial creation audit log")
    fun testCreateTask() {
        val task =
            service.createTask(
                title = "Build REST API",
                description = "Implement endpoints for board and tasks",
                priority = TaskPriority.HIGH,
                assignee = "agent-1",
                tags = setOf("backend", "ktor"),
                githubRepo = "0oWoodenDooro0/AiKanban",
                githubIssueUrl = "https://github.com/0oWoodenDooro0/AiKanban/issues/4",
                status = "TODO",
                operator = "tech-lead",
            )

        assertTrue(task.id > 0)
        assertEquals("Build REST API", task.title)
        assertEquals("Implement endpoints for board and tasks", task.description)
        assertEquals(TaskPriority.HIGH, task.priority)
        assertEquals("agent-1", task.assignee)
        assertEquals(setOf("backend", "ktor"), task.tags)
        assertEquals("0oWoodenDooro0/AiKanban", task.githubRepo)
        assertEquals("https://github.com/0oWoodenDooro0/AiKanban/issues/4", task.githubIssueUrl)
        assertEquals("TODO", task.status)
        assertNull(task.completedAt)

        // Verify initial audit log entry
        assertEquals(1, task.logs.size)
        assertEquals("tech-lead", task.logs[0].operator)
        assertEquals("TODO", task.logs[0].toStatus)
        assertTrue(task.logs[0].comment.contains("Task created"))
    }

    @Test
    @DisplayName("Should reject blank task title or invalid initial status")
    fun testCreateTaskValidation() {
        assertFailsWith<TaskValidationException> {
            service.createTask(title = "   ")
        }

        assertFailsWith<ColumnNotFoundException> {
            service.createTask(title = "Valid Title", status = "NON_EXISTENT_COLUMN")
        }
    }

    @Test
    @DisplayName("Should get, update, and delete task")
    fun testGetUpdateDeleteTask() {
        val created = service.createTask(title = "Initial Title", description = "Initial Desc")

        val fetched = service.getTask(created.id)
        assertEquals(created.id, fetched.id)
        assertEquals("Initial Title", fetched.title)

        val fetchedOrNull = service.getTaskOrNull(created.id)
        assertNotNull(fetchedOrNull)
        assertNull(service.getTaskOrNull(999999))

        // Update task
        val updated =
            service.updateTask(
                taskId = created.id,
                title = "Updated Title",
                description = "Updated Desc",
                priority = TaskPriority.URGENT,
                assignee = "agent-2",
                tags = setOf("urgent", "release"),
                githubPrUrl = "https://github.com/0oWoodenDooro0/AiKanban/pull/1",
                operator = "admin",
                comment = "Updated priority and tags",
            )
        assertEquals("Updated Title", updated.title)
        assertEquals("Updated Desc", updated.description)
        assertEquals(TaskPriority.URGENT, updated.priority)
        assertEquals("agent-2", updated.assignee)
        assertEquals(setOf("urgent", "release"), updated.tags)
        assertEquals("https://github.com/0oWoodenDooro0/AiKanban/pull/1", updated.githubPrUrl)
        assertEquals(2, updated.logs.size) // Creation log + Update log

        // Blank title update should fail
        assertFailsWith<TaskValidationException> {
            service.updateTask(taskId = created.id, title = "  ")
        }

        // Update non-existent task
        assertFailsWith<TaskNotFoundException> {
            service.updateTask(taskId = 999999, title = "New Title")
        }

        // Delete task
        val deleted = service.deleteTask(created.id)
        assertTrue(deleted)
        assertNull(service.getTaskOrNull(created.id))
        assertFailsWith<TaskNotFoundException> {
            service.getTask(created.id)
        }
        assertFalse(service.deleteTask(999999))
    }

    // ==========================================
    // 3. Query Filtering & Sorting Tests
    // ==========================================

    @Test
    @DisplayName("Should list and filter tasks by status, assignee, tag, and priority")
    fun testListTasksFiltering() {
        val t1 =
            service.createTask(
                title = "Task 1",
                status = "TODO",
                assignee = "alice",
                tags = setOf("backend", "api"),
                priority = TaskPriority.HIGH,
            )
        val t2 =
            service.createTask(
                title = "Task 2",
                status = "IN_PROGRESS",
                assignee = "bob",
                tags = setOf("frontend", "ui"),
                priority = TaskPriority.MEDIUM,
            )
        val t3 =
            service.createTask(
                title = "Task 3",
                status = "TODO",
                assignee = "bob",
                tags = setOf("backend", "db"),
                priority = TaskPriority.HIGH,
            )
        val t4 =
            service.createTask(
                title = "Task 4",
                status = "REVIEW",
                assignee = "alice",
                tags = setOf("docs"),
                priority = TaskPriority.LOW,
            )
        val t5 =
            service.createTask(
                title = "Task 5",
                status = "DONE",
                assignee = "alice",
                tags = setOf("backend"),
                priority = TaskPriority.URGENT,
            )

        // Filter by status
        val todoList = service.listTasks(status = "TODO")
        assertEquals(2, todoList.size)
        assertTrue(todoList.all { it.status == "TODO" })

        // Filter by assignee
        val bobList = service.listTasks(assignee = "bob")
        assertEquals(2, bobList.size)
        assertTrue(bobList.all { it.assignee == "bob" })

        // Filter by tag
        val backendList = service.listTasks(tag = "backend")
        assertEquals(3, backendList.size)
        assertTrue(backendList.all { it.tags.contains("backend") })

        // Filter by priority
        val highList = service.listTasks(priority = TaskPriority.HIGH)
        assertEquals(2, highList.size)
        assertTrue(highList.all { it.priority == TaskPriority.HIGH })

        // Combined filter
        val combined =
            service.listTasks(
                status = "TODO",
                assignee = "bob",
                tag = "backend",
                priority = TaskPriority.HIGH,
            )
        assertEquals(1, combined.size)
        assertEquals(t3.id, combined.first().id)

        // No matches
        val noMatch = service.listTasks(status = "PENDING")
        assertTrue(noMatch.isEmpty())
    }

    // ==========================================
    // 4. Free Column Movement & Terminal State Tests
    // ==========================================

    @Test
    @DisplayName("Should freely move task across all board columns and manage completedAt timestamp")
    fun testFreeColumnMovementAndCompletionTimestamp() {
        val task = service.createTask(title = "Workflow Movement Task", status = "TODO")
        assertNull(task.completedAt)

        // TODO -> IN_PROGRESS
        val inProgress = service.moveTask(task.id, "IN_PROGRESS", operator = "agent-1", comment = "Started work")
        assertEquals("IN_PROGRESS", inProgress.status)
        assertNull(inProgress.completedAt)

        // IN_PROGRESS -> REVIEW
        val review = service.moveTask(task.id, "REVIEW", operator = "agent-1", prUrl = "https://github.com/pr/1")
        assertEquals("REVIEW", review.status)
        assertEquals("https://github.com/pr/1", review.githubPrUrl)
        assertNull(review.completedAt)

        // REVIEW -> REQUEST
        val request = service.moveTask(task.id, "REQUEST", operator = "reviewer", comment = "Please fix tests")
        assertEquals("REQUEST", request.status)
        assertNull(request.completedAt)

        // REQUEST -> PENDING
        val pending = service.moveTask(task.id, "PENDING", operator = "agent-1", comment = "Waiting for upstream API")
        assertEquals("PENDING", pending.status)
        assertNull(pending.completedAt)

        // PENDING -> REOPEN
        val reopen = service.moveTask(task.id, "REOPEN", operator = "manager", comment = "Upstream ready, resumed")
        assertEquals("REOPEN", reopen.status)
        assertNull(reopen.completedAt)

        // REOPEN -> DONE (Terminal state: sets completedAt)
        val done = service.moveTask(task.id, "DONE", operator = "reviewer", comment = "Approved and merged")
        assertEquals("DONE", done.status)
        assertNotNull(done.completedAt)

        // DONE -> REOPEN (Moving out of terminal state: clears completedAt)
        val reopened = service.moveTask(task.id, "REOPEN", operator = "qa-agent", comment = "Found regression")
        assertEquals("REOPEN", reopened.status)
        assertNull(reopened.completedAt)

        // Direct arbitrary jump: REOPEN -> REVIEW
        val directJump = service.moveTask(task.id, "REVIEW", operator = "agent-1", comment = "Hotfix submitted")
        assertEquals("REVIEW", directJump.status)
        assertNull(directJump.completedAt)

        // Direct arbitrary jump: REVIEW -> DONE
        val completedAgain = service.moveTask(task.id, "DONE", operator = "reviewer", comment = "Hotfix approved")
        assertEquals("DONE", completedAgain.status)
        assertNotNull(completedAgain.completedAt)
    }

    @Test
    @DisplayName("Should fail moving task to non-existent column or for non-existent task")
    fun testMoveTaskValidation() {
        val task = service.createTask(title = "Task for validation")

        assertFailsWith<ColumnNotFoundException> {
            service.moveTask(task.id, "NON_EXISTENT_STATUS", operator = "user")
        }

        assertFailsWith<TaskNotFoundException> {
            service.moveTask(999999, "IN_PROGRESS", operator = "user")
        }
    }

    // ==========================================
    // 5. Multi-Agent Lifecycle Operations Tests
    // ==========================================

    @Test
    @DisplayName("Should claim next available task respecting priority ordering and FIFO")
    fun testClaimNextTaskPriorityAndFifo() {
        val tLow = service.createTask(title = "Low task", priority = TaskPriority.LOW, status = "TODO")
        Thread.sleep(10)
        val tMed1 = service.createTask(title = "Med task 1", priority = TaskPriority.MEDIUM, status = "TODO")
        Thread.sleep(10)
        val tMed2 = service.createTask(title = "Med task 2", priority = TaskPriority.MEDIUM, status = "TODO")
        Thread.sleep(10)
        val tHigh = service.createTask(title = "High task", priority = TaskPriority.HIGH, status = "TODO")
        Thread.sleep(10)
        val tUrgent = service.createTask(title = "Urgent task", priority = TaskPriority.URGENT, status = "TODO")

        // Claim 1: Should pick URGENT
        val c1 = service.claimNextTask(agentName = "agent-claude")
        assertNotNull(c1)
        assertEquals(tUrgent.id, c1.id)
        assertEquals("IN_PROGRESS", c1.status)
        assertEquals("agent-claude", c1.assignee)

        // Claim 2: Should pick HIGH
        val c2 = service.claimNextTask(agentName = "agent-gemini")
        assertNotNull(c2)
        assertEquals(tHigh.id, c2.id)

        // Claim 3: Should pick Med1 (FIFO)
        val c3 = service.claimNextTask(agentName = "agent-gpt")
        assertNotNull(c3)
        assertEquals(tMed1.id, c3.id)

        // Claim 4: Should pick Med2
        val c4 = service.claimNextTask(agentName = "agent-codex")
        assertNotNull(c4)
        assertEquals(tMed2.id, c4.id)

        // Claim 5: Should pick LOW
        val c5 = service.claimNextTask(agentName = "agent-llama")
        assertNotNull(c5)
        assertEquals(tLow.id, c5.id)

        // Claim 6: Empty queue returns null
        val c6 = service.claimNextTask(agentName = "agent-extra")
        assertNull(c6)
    }

    @Test
    @DisplayName("Should claim task matching specific tag filter")
    fun testClaimNextTaskTagFiltering() {
        val t1 = service.createTask(title = "Kotlin Task", tags = setOf("kotlin", "backend"), priority = TaskPriority.LOW)
        val t2 = service.createTask(title = "Python Task", tags = setOf("python", "ai"), priority = TaskPriority.URGENT)

        val claimed = service.claimNextTask(agentName = "kotlin-specialist", tag = "kotlin")
        assertNotNull(claimed)
        assertEquals(t1.id, claimed.id)
        assertEquals("kotlin-specialist", claimed.assignee)

        val noneLeft = service.claimNextTask(agentName = "kotlin-specialist", tag = "kotlin")
        assertNull(noneLeft)
    }

    @Test
    @DisplayName("Should support multi-agent lifecycle operations via moveTask, claimNextTask, and releaseTask")
    fun testLifecycleHelpers() {
        val task = service.createTask(title = "Full Lifecycle Task", status = "TODO")

        // 1. Claim
        val claimed = service.claimNextTask(agentName = "dev-agent")
        assertNotNull(claimed)
        assertEquals("IN_PROGRESS", claimed.status)
        assertEquals("dev-agent", claimed.assignee)

        // 2. Submit for review -> REVIEW via moveTask
        val inReview =
            service.moveTask(
                taskId = task.id,
                toStatus = "REVIEW",
                operator = "dev-agent",
                prUrl = "https://github.com/0oWoodenDooro0/AiKanban/pull/10",
                comment = "Ready for review",
            )
        assertEquals("REVIEW", inReview.status)
        assertEquals("https://github.com/0oWoodenDooro0/AiKanban/pull/10", inReview.githubPrUrl)

        // 3. Request changes -> REQUEST via moveTask
        val requested =
            service.moveTask(
                taskId = task.id,
                toStatus = "REQUEST",
                operator = "senior-reviewer",
                comment = "Please add edge-case unit tests",
            )
        assertEquals("REQUEST", requested.status)

        // 4. Mark pending -> PENDING via moveTask
        val pending =
            service.moveTask(
                taskId = task.id,
                toStatus = "PENDING",
                operator = "dev-agent",
                comment = "Waiting for design asset clarification",
            )
        assertEquals("PENDING", pending.status)

        // 5. Submit for review again -> REVIEW via moveTask
        val reviewAgain =
            service.moveTask(
                taskId = task.id,
                toStatus = "REVIEW",
                operator = "dev-agent",
                comment = "Added requested tests",
            )
        assertEquals("REVIEW", reviewAgain.status)

        // 6. Approve and complete -> DONE via moveTask
        val completed =
            service.moveTask(
                taskId = task.id,
                toStatus = "DONE",
                operator = "senior-reviewer",
                comment = "All tests pass, approved and merged",
            )
        assertEquals("DONE", completed.status)
        assertNotNull(completed.completedAt)

        // 7. Reopen task -> REOPEN via moveTask (completedAt reset to null)
        val reopened =
            service.moveTask(
                taskId = task.id,
                toStatus = "REOPEN",
                operator = "qa-engineer",
                comment = "Performance degradation detected under load",
            )
        assertEquals("REOPEN", reopened.status)
        assertNull(reopened.completedAt)

        // 8. Release task -> TODO via releaseTask
        val released =
            service.releaseTask(
                taskId = task.id,
                operator = "qa-engineer",
                targetStatus = "TODO",
                comment = "Returning to backlog for rescheduling",
            )
        assertEquals("TODO", released.status)
        assertNull(released.assignee)
    }

    // ==========================================
    // 6. Audit Logging & Comment Tests
    // ==========================================

    @Test
    @DisplayName("Should add custom comment / audit log to a task and retrieve complete log history")
    fun testAddCommentAndGetTaskLogs() {
        val task = service.createTask(title = "Task with logs", operator = "system")
        assertEquals(1, service.getTaskLogs(task.id).size)

        val entry1 =
            service.addComment(
                taskId = task.id,
                operator = "ci-bot",
                comment = "CI build passed successfully",
                commitHash = "abc1234",
            )
        assertEquals("ci-bot", entry1.operator)
        assertEquals("CI build passed successfully", entry1.comment)
        assertEquals("abc1234", entry1.commitHash)

        val entry2 =
            service.addComment(
                taskId = task.id,
                operator = "human-dev",
                comment = "Reviewed changes locally",
            )
        assertEquals("human-dev", entry2.operator)

        val logs = service.getTaskLogs(task.id)
        assertEquals(3, logs.size)
        assertEquals("system", logs[0].operator)
        assertEquals("ci-bot", logs[1].operator)
        assertEquals("human-dev", logs[2].operator)

        // Non-existent task should throw TaskNotFoundException
        assertFailsWith<TaskNotFoundException> {
            service.addComment(taskId = 999999, operator = "bot", comment = "test")
        }
        assertFailsWith<TaskNotFoundException> {
            service.getTaskLogs(999999)
        }
    }

    // ==========================================
    // 7. Reactive Event Streaming Tests
    // ==========================================

    @Test
    @DisplayName("Should emit reactive KanbanEvents for task and column lifecycle mutations")
    fun testReactiveEventStreaming() =
        runBlocking {
            val collectedEvents = java.util.concurrent.CopyOnWriteArrayList<KanbanEvent>()
            val job =
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    service.events.collect { event ->
                        collectedEvents.add(event)
                    }
                }

            // Wait until collector is registered
            kotlinx.coroutines.delay(100)

            // Trigger events
            val task = service.createTask(title = "Event Task", status = "TODO")
            val moved = service.moveTask(task.id, "IN_PROGRESS", operator = "dev-agent", comment = "Moving")
            val customCol = service.createColumn(BoardColumn("QA", "QA Testing", 8))
            val updatedCol = service.updateColumn(customCol.copy(name = "QA & Verification"))
            service.deleteTask(task.id)
            service.deleteColumn("QA")

            // Give slight time for coroutine emissions
            kotlinx.coroutines.delay(100)
            job.cancel()

            assertTrue(collectedEvents.any { it is KanbanEvent.TaskCreated && it.task.title == "Event Task" })
            assertTrue(collectedEvents.any { it is KanbanEvent.TaskMoved && it.toStatus == "IN_PROGRESS" })
            assertTrue(collectedEvents.any { it is KanbanEvent.ColumnCreated && it.column.id == "QA" })
            assertTrue(collectedEvents.any { it is KanbanEvent.ColumnUpdated && it.column.name == "QA & Verification" })
            assertTrue(collectedEvents.any { it is KanbanEvent.TaskDeleted && it.taskId == task.id })
            assertTrue(collectedEvents.any { it is KanbanEvent.ColumnDeleted && it.columnId == "QA" })
        }

    // ==========================================
    // 8. High-Concurrency Multi-Agent Claims
    // ==========================================

    @Test
    @DisplayName("High-concurrency test: 20 concurrent workers claiming 10 tasks without race conditions")
    fun testConcurrentClaimingNoDuplicate() {
        val taskCount = 10
        val createdTasks =
            (1..taskCount).map { i ->
                service.createTask(title = "Concurrent Task $i", status = "TODO", priority = TaskPriority.MEDIUM)
            }
        assertEquals(taskCount, createdTasks.size)

        val workerCount = 20
        val executor = Executors.newFixedThreadPool(workerCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(workerCount)

        val claimedTaskIds = ConcurrentHashMap.newKeySet<Int>()
        val successfulClaims = AtomicInteger(0)

        for (i in 1..workerCount) {
            val agentName = "agent-$i"
            executor.submit {
                try {
                    startLatch.await()
                    val claimed =
                        service.claimNextTask(
                            fromStatus = "TODO",
                            toStatus = "IN_PROGRESS",
                            agentName = agentName,
                        )
                    if (claimed != null) {
                        successfulClaims.incrementAndGet()
                        claimedTaskIds.add(claimed.id)
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        // Trigger all workers simultaneously
        startLatch.countDown()
        val finishedInTime = doneLatch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertTrue(finishedInTime, "All workers should finish within timeout")
        assertEquals(taskCount, successfulClaims.get(), "Exactly $taskCount tasks should be claimed")
        assertEquals(taskCount, claimedTaskIds.size, "All claimed task IDs must be unique (no duplicate claims)")

        // Verify in DB that 0 tasks remain in TODO and all 10 are IN_PROGRESS
        val remainingTodo = service.listTasks(status = "TODO")
        val inProgressTasks = service.listTasks(status = "IN_PROGRESS")
        assertEquals(0, remainingTodo.size)
        assertEquals(taskCount, inProgressTasks.size)
    }

    @Test
    @DisplayName("Coroutines concurrency test: concurrent claims and moves")
    fun testCoroutinesConcurrency() =
        runBlocking {
            val taskCount = 15
            (1..taskCount).forEach { i ->
                service.createTask(title = "Coroutine Task $i", status = "TODO")
            }

            val results =
                (1..30).map { i ->
                    async(Dispatchers.IO) {
                        service.claimNextTask(
                            fromStatus = "TODO",
                            toStatus = "IN_PROGRESS",
                            agentName = "async-agent-$i",
                        )
                    }
                }.awaitAll()

            val nonNullClaims = results.filterNotNull()
            assertEquals(taskCount, nonNullClaims.size)
            assertEquals(taskCount, nonNullClaims.map { it.id }.toSet().size)
        }

    @Test
    @DisplayName("Should create and update task with explicit branch name")
    fun testCreateAndUpdateTaskWithBranch() {
        val created =
            service.createTask(
                title = "Task with branch",
                branch = "feature/service-test-branch",
                status = "TODO",
            )
        assertNotNull(created)
        assertEquals("feature/service-test-branch", created.branch)

        val fetched = service.getTask(created.id)
        assertEquals("feature/service-test-branch", fetched.branch)

        val updated =
            service.updateTask(
                taskId = created.id,
                branch = "feature/service-test-branch-v2",
            )
        assertEquals("feature/service-test-branch-v2", updated.branch)

        val fetchedUpdated = service.getTask(created.id)
        assertEquals("feature/service-test-branch-v2", fetchedUpdated.branch)
    }
}
