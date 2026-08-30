package aikanban.provider.ingestion

import aikanban.model.TaskPriority
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssueIngestionPipelineTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var service: KanbanService
    private lateinit var pipeline: IssueIngestionPipeline

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("ingestion_test.db").toFile()
        service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}"))
        pipeline = DefaultIssueIngestionPipeline(service)
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    @Test
    @DisplayName("Should ingest new open issues and persist tasks with correct fields and priority mapping")
    fun testIngestNewIssues() =
        runBlocking {
            val issues =
                listOf(
                    RawIssueData(
                        id = "1",
                        number = 10,
                        title = "Implement Auth Pipeline",
                        body = "JWT token handling",
                        state = "open",
                        htmlUrl = "https://github.com/myorg/myapp/issues/10",
                        assignee = "alice",
                        labels = listOf("backend", "priority:urgent"),
                    ),
                    RawIssueData(
                        id = "2",
                        number = 11,
                        title = "Fix UI Layout",
                        body = "Responsive CSS fix",
                        state = "open",
                        htmlUrl = "https://github.com/myorg/myapp/issues/11",
                        assignee = null,
                        labels = listOf("frontend", "low"),
                    ),
                )

            val result =
                pipeline.ingest(
                    issues = issues,
                    repo = "myorg/myapp",
                    targetStatus = "TODO",
                    operator = "test-sync",
                    dryRun = false,
                    providerName = "github",
                )

            assertEquals("github", result.provider)
            assertEquals("myorg/myapp", result.repo)
            assertEquals(2, result.totalFetched)
            assertEquals(2, result.createdCount)
            assertEquals(0, result.updatedCount)
            assertEquals(0, result.skippedCount)
            assertEquals(2, result.tasks.size)

            val tasks = service.listTasks()
            assertEquals(2, tasks.size)

            val task1 = tasks.find { it.title == "Implement Auth Pipeline" }
            assertNotNull(task1)
            assertEquals("JWT token handling", task1.description)
            assertEquals(TaskPriority.URGENT, task1.priority)
            assertEquals("alice", task1.assignee)
            assertEquals(setOf("backend", "priority:urgent"), task1.tags)
            assertEquals("myorg/myapp", task1.githubRepo)
            assertEquals("https://github.com/myorg/myapp/issues/10", task1.githubIssueUrl)
            assertEquals("TODO", task1.status)

            val task2 = tasks.find { it.title == "Fix UI Layout" }
            assertNotNull(task2)
            assertEquals(TaskPriority.LOW, task2.priority)
            assertNull(task2.assignee)
            assertEquals(setOf("frontend", "low"), task2.tags)
        }

    @Test
    @DisplayName("Should prevent duplicates and update existing task when syncing repeatedly")
    fun testDuplicatePreventionAndUpdate() =
        runBlocking {
            val initialIssue =
                RawIssueData(
                    id = "1",
                    number = 5,
                    title = "Initial Task Title",
                    body = "Initial Description",
                    state = "open",
                    htmlUrl = "https://github.com/org/repo/issues/5",
                    assignee = null,
                    labels = listOf("bug"),
                )

            val res1 = pipeline.ingest(listOf(initialIssue), repo = "org/repo")
            assertEquals(1, res1.createdCount)
            assertEquals(0, res1.updatedCount)
            assertEquals(1, service.listTasks().size)

            val updatedIssue =
                RawIssueData(
                    id = "1",
                    number = 5,
                    title = "Updated Task Title",
                    body = "Updated Body",
                    state = "open",
                    htmlUrl = "https://github.com/org/repo/issues/5",
                    assignee = "bob",
                    labels = listOf("bug", "priority:high"),
                )

            val res2 = pipeline.ingest(listOf(updatedIssue), repo = "org/repo")
            assertEquals(0, res2.createdCount)
            assertEquals(1, res2.updatedCount)
            assertEquals(1, service.listTasks().size)

            val task = service.listTasks().first()
            assertEquals("Updated Task Title", task.title)
            assertEquals("Updated Body", task.description)
            assertEquals("bob", task.assignee)
            assertEquals(TaskPriority.HIGH, task.priority)
            assertTrue(task.tags.contains("priority:high"))
        }

    @Test
    @DisplayName("Should sync closed issues to DONE status and set completed timestamp")
    fun testSyncClosedIssues() =
        runBlocking {
            val closedIssue =
                RawIssueData(
                    id = "1",
                    number = 20,
                    title = "Completed Issue",
                    state = "closed",
                    htmlUrl = "https://github.com/org/repo/issues/20",
                )

            val result = pipeline.ingest(listOf(closedIssue), repo = "org/repo")
            assertEquals(1, result.createdCount)

            val task = service.listTasks().first()
            assertEquals("DONE", task.status)
            assertNotNull(task.completedAt)
        }

    @Test
    @DisplayName("Should preview tasks without modifying database in dry-run mode")
    fun testDryRunIngestion() =
        runBlocking {
            val issue =
                RawIssueData(
                    id = "1",
                    number = 100,
                    title = "Dry Run Issue",
                    body = "Preview content",
                    state = "open",
                    htmlUrl = "https://github.com/org/repo/issues/100",
                    labels = listOf("p0"),
                )

            val result = pipeline.ingest(listOf(issue), repo = "org/repo", dryRun = true)

            assertEquals(1, result.totalFetched)
            assertEquals(1, result.createdCount)
            assertEquals(1, result.tasks.size)
            assertEquals("Dry Run Issue", result.tasks.first().title)
            assertEquals(TaskPriority.URGENT, result.tasks.first().priority)
            assertEquals(0, service.listTasks().size) // DB remains empty
        }

    @Test
    @DisplayName("Should handle pull request reference URL on issue")
    fun testIngestPullRequestReference() =
        runBlocking {
            val prIssue =
                RawIssueData(
                    id = "1",
                    number = 50,
                    title = "PR Linked Feature",
                    state = "open",
                    htmlUrl = "https://github.com/org/repo/issues/50",
                    isPullRequest = true,
                    prUrl = "https://github.com/org/repo/pull/50",
                )

            val result = pipeline.ingest(listOf(prIssue), repo = "org/repo")
            assertEquals(1, result.createdCount)

            val task = service.listTasks().first()
            assertEquals("https://github.com/org/repo/pull/50", task.githubPrUrl)
        }
}
