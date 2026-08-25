package aikanban.repository

import aikanban.model.BoardColumn
import aikanban.model.Task
import aikanban.model.TaskLogEntry
import aikanban.model.TaskPriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        assertEquals(5, columns.size)
        assertEquals(listOf("TODO", "IN_PROGRESS", "PR_REVIEW", "REQUEST", "DONE"), columns.map { it.id })
        
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
        val customCol = BoardColumn(
            id = "QA",
            name = "Quality Assurance",
            order = 5,
            color = "#A855F7",
            isTerminal = false
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
        val newTask = Task(
            title = "Implement OAuth2 login",
            description = "Support Google & GitHub SSO",
            status = "TODO",
            priority = TaskPriority.HIGH,
            assignee = "agent-claude",
            tags = setOf("auth", "security", "backend"),
            githubRepo = "0oWoodenDooro0/AiKanban",
            githubIssueUrl = "https://github.com/0oWoodenDooro0/AiKanban/issues/10"
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
        val taskToUpdate = retrieved.copy(
            title = "Implement OAuth2 & SAML login",
            priority = TaskPriority.URGENT,
            tags = setOf("auth", "enterprise")
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
    @DisplayName("Should move task, record audit logs, and handle terminal completion timestamp")
    fun testMoveTaskAndLogAudit() {
        val task = repository.createTask(Task(title = "Refactor logging", status = "TODO"))
        assertNull(task.completedAt)
        assertTrue(task.logs.isEmpty())

        // Move TODO -> IN_PROGRESS
        val inProgress = repository.moveTask(
            taskId = task.id,
            toStatus = "IN_PROGRESS",
            operator = "agent-gemini",
            comment = "Started working on logging refactor",
            assignee = "agent-gemini"
        )
        assertEquals("IN_PROGRESS", inProgress.status)
        assertEquals("agent-gemini", inProgress.assignee)
        assertNull(inProgress.completedAt)
        assertEquals(1, inProgress.logs.size)
        assertEquals("TODO", inProgress.logs[0].fromStatus)
        assertEquals("IN_PROGRESS", inProgress.logs[0].toStatus)
        assertEquals("agent-gemini", inProgress.logs[0].operator)
        assertEquals("Started working on logging refactor", inProgress.logs[0].comment)

        // Move IN_PROGRESS -> PR_REVIEW
        val prReview = repository.moveTask(
            taskId = task.id,
            toStatus = "PR_REVIEW",
            operator = "agent-gemini",
            comment = "Opened PR #42",
            prUrl = "https://github.com/0oWoodenDooro0/AiKanban/pull/42"
        )
        assertEquals("PR_REVIEW", prReview.status)
        assertEquals(2, prReview.logs.size)
        assertEquals("https://github.com/0oWoodenDooro0/AiKanban/pull/42", prReview.githubPrUrl)

        // Move PR_REVIEW -> DONE (Terminal state)
        val done = repository.moveTask(
            taskId = task.id,
            toStatus = "DONE",
            operator = "reviewer-bob",
            comment = "LGTM! Merged."
        )
        assertEquals("DONE", done.status)
        assertNotNull(done.completedAt)
        assertEquals(3, done.logs.size)

        // Move DONE -> IN_PROGRESS (Reopened, terminal completedAt should be cleared)
        val reopened = repository.moveTask(
            taskId = task.id,
            toStatus = "IN_PROGRESS",
            operator = "qa-charlie",
            comment = "Reopening due to edge case bug"
        )
        assertEquals("IN_PROGRESS", reopened.status)
        assertNull(reopened.completedAt)
        assertEquals(4, reopened.logs.size)
    }

    @Test
    @DisplayName("Should claim next available task respecting priority ordering and FIFO")
    fun testClaimNextTaskPriorityOrdering() {
        val tLow = repository.createTask(Task(title = "Low task", priority = TaskPriority.LOW, status = "TODO"))
        Thread.sleep(10)
        val tMed1 = repository.createTask(Task(title = "Med task 1", priority = TaskPriority.MEDIUM, status = "TODO"))
        Thread.sleep(10)
        val tMed2 = repository.createTask(Task(title = "Med task 2", priority = TaskPriority.MEDIUM, status = "TODO"))
        Thread.sleep(10)
        val tHigh = repository.createTask(Task(title = "High task", priority = TaskPriority.HIGH, status = "TODO"))
        Thread.sleep(10)
        val tUrgent = repository.createTask(Task(title = "Urgent task", priority = TaskPriority.URGENT, status = "TODO"))

        // 1st claim: should pick URGENT
        val claim1 = repository.claimNextTask(fromStatus = "TODO", toStatus = "IN_PROGRESS", agentName = "agent-1")
        assertNotNull(claim1)
        assertEquals(tUrgent.id, claim1.id)
        assertEquals("IN_PROGRESS", claim1.status)
        assertEquals("agent-1", claim1.assignee)

        // 2nd claim: should pick HIGH
        val claim2 = repository.claimNextTask(fromStatus = "TODO", toStatus = "IN_PROGRESS", agentName = "agent-2")
        assertNotNull(claim2)
        assertEquals(tHigh.id, claim2.id)

        // 3rd claim: should pick Med1 (FIFO before Med2)
        val claim3 = repository.claimNextTask(fromStatus = "TODO", toStatus = "IN_PROGRESS", agentName = "agent-3")
        assertNotNull(claim3)
        assertEquals(tMed1.id, claim3.id)

        // 4th claim: should pick Med2
        val claim4 = repository.claimNextTask(fromStatus = "TODO", toStatus = "IN_PROGRESS", agentName = "agent-4")
        assertNotNull(claim4)
        assertEquals(tMed2.id, claim4.id)

        // 5th claim: should pick LOW
        val claim5 = repository.claimNextTask(fromStatus = "TODO", toStatus = "IN_PROGRESS", agentName = "agent-5")
        assertNotNull(claim5)
        assertEquals(tLow.id, claim5.id)

        // 6th claim: queue is empty, returns null
        val claim6 = repository.claimNextTask(fromStatus = "TODO", toStatus = "IN_PROGRESS", agentName = "agent-6")
        assertNull(claim6)
    }

    @Test
    @DisplayName("Should claim task matching specific tag filter only")
    fun testClaimNextTaskTagFiltering() {
        val t1 = repository.createTask(Task(title = "Task with python tag", status = "TODO", tags = setOf("python", "ai")))
        val t2 = repository.createTask(Task(title = "Task with kotlin tag", status = "TODO", tags = setOf("kotlin", "backend"), priority = TaskPriority.URGENT))

        // Claim with tag = 'python' should claim t1 even though t2 has higher priority
        val claimed = repository.claimNextTask(fromStatus = "TODO", toStatus = "IN_PROGRESS", agentName = "python-agent", tag = "python")
        assertNotNull(claimed)
        assertEquals(t1.id, claimed.id)
        assertEquals("python-agent", claimed.assignee)

        // Claim again with tag = 'python' should return null
        val none = repository.claimNextTask(fromStatus = "TODO", toStatus = "IN_PROGRESS", agentName = "python-agent", tag = "python")
        assertNull(none)
    }

    @Test
    @DisplayName("Should support appending manual logs to a task")
    fun testAppendLog() {
        val task = repository.createTask(Task(title = "Task with custom logs", status = "TODO"))
        val entry1 = TaskLogEntry(
            operator = "ci-bot",
            comment = "Build #123 passed",
            commitHash = "abc1234"
        )
        repository.appendLog(task.id, entry1)

        val retrieved = repository.getTask(task.id)
        assertNotNull(retrieved)
        assertEquals(1, retrieved.logs.size)
        assertEquals("ci-bot", retrieved.logs[0].operator)
        assertEquals("Build #123 passed", retrieved.logs[0].comment)
        assertEquals("abc1234", retrieved.logs[0].commitHash)
    }

    @Test
    @DisplayName("High-concurrency test: 20 concurrent workers claiming 10 tasks without race conditions")
    fun testConcurrentClaimingNoDuplicate() {
        val taskCount = 10
        val createdTasks = (1..taskCount).map { i ->
            repository.createTask(Task(title = "Concurrent Task $i", status = "TODO", priority = TaskPriority.MEDIUM))
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
                    val claimed = repository.claimNextTask(
                        fromStatus = "TODO",
                        toStatus = "IN_PROGRESS",
                        agentName = agentName
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
        val remainingTodo = repository.listTasks(status = "TODO")
        val inProgressTasks = repository.listTasks(status = "IN_PROGRESS")
        assertEquals(0, remainingTodo.size)
        assertEquals(taskCount, inProgressTasks.size)
    }

    @Test
    @DisplayName("Coroutines concurrency test: concurrent claims and moves")
    fun testCoroutinesConcurrency() = runBlocking {
        val taskCount = 15
        (1..taskCount).forEach { i ->
            repository.createTask(Task(title = "Coroutine Task $i", status = "TODO"))
        }

        val results = (1..30).map { i ->
            async(Dispatchers.IO) {
                repository.claimNextTask(
                    fromStatus = "TODO",
                    toStatus = "IN_PROGRESS",
                    agentName = "async-agent-$i"
                )
            }
        }.awaitAll()

        val nonNullClaims = results.filterNotNull()
        assertEquals(taskCount, nonNullClaims.size)
        assertEquals(taskCount, nonNullClaims.map { it.id }.toSet().size)
    }
}
