package aikanban.github

import aikanban.github.client.GitHubClient
import aikanban.github.model.GitHubIssueDto
import aikanban.github.model.GitHubLabelDto
import aikanban.github.model.GitHubPullRequestRefDto
import aikanban.github.model.GitHubUserDto
import aikanban.github.service.DefaultGitHubSyncService
import aikanban.github.service.GitHubSyncService
import aikanban.model.TaskPriority
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubSyncServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var repository: SqliteTaskRepository
    private lateinit var kanbanService: KanbanService

    private class MockGitHubClient : GitHubClient {
        val issues = mutableListOf<GitHubIssueDto>()

        override suspend fun fetchRepositoryIssues(
            owner: String,
            repo: String,
            state: String,
            labels: Set<String>,
            token: String?,
            page: Int,
            perPage: Int,
        ): List<GitHubIssueDto> {
            return issues.filter { issue ->
                val stateMatch =
                    when (state.lowercase()) {
                        "open" -> issue.state == "open"
                        "closed" -> issue.state == "closed"
                        else -> true
                    }
                val labelMatch = if (labels.isEmpty()) true else issue.labels.any { labels.contains(it.name) }
                stateMatch && labelMatch
            }
        }

        override suspend fun fetchIssue(
            owner: String,
            repo: String,
            number: Int,
            token: String?,
        ): GitHubIssueDto? {
            return issues.find { it.number == number }
        }

        override fun close() {}
    }

    private lateinit var mockGitHubClient: MockGitHubClient
    private lateinit var syncService: GitHubSyncService

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("sync_test.db").toFile()
        repository = SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}")
        kanbanService = DefaultKanbanService(repository)
        mockGitHubClient = MockGitHubClient()
        syncService = DefaultGitHubSyncService(kanbanService, mockGitHubClient)
    }

    @AfterEach
    fun tearDown() {
        kanbanService.close()
    }

    @Nested
    @DisplayName("Repository Sync Operations")
    inner class RepositorySyncTests {
        @Test
        @DisplayName("Should sync open issues into new Kanban tasks with correct fields")
        fun testSyncNewIssues() =
            runTest {
                mockGitHubClient.issues.addAll(
                    listOf(
                        GitHubIssueDto(
                            id = 1,
                            number = 10,
                            title = "Implement User Auth",
                            body = "JWT authentication support",
                            state = "open",
                            htmlUrl = "https://github.com/myorg/myapp/issues/10",
                            assignee = GitHubUserDto(id = 101, login = "alice"),
                            labels =
                                listOf(
                                    GitHubLabelDto(id = 1, name = "backend"),
                                    GitHubLabelDto(id = 2, name = "priority:high"),
                                ),
                        ),
                        GitHubIssueDto(
                            id = 2,
                            number = 11,
                            title = "Fix CSS Overflow",
                            body = "Fix flexbox overflow on mobile",
                            state = "open",
                            htmlUrl = "https://github.com/myorg/myapp/issues/11",
                            assignee = null,
                            labels =
                                listOf(
                                    GitHubLabelDto(id = 3, name = "frontend"),
                                    GitHubLabelDto(id = 4, name = "priority:low"),
                                ),
                        ),
                    ),
                )

                val result = syncService.syncRepository("myorg/myapp")

                assertEquals(2, result.totalFetched)
                assertEquals(2, result.createdCount)
                assertEquals(0, result.updatedCount)
                assertEquals(0, result.skippedCount)
                assertEquals(2, result.tasks.size)

                val allTasks = kanbanService.listTasks()
                assertEquals(2, allTasks.size)

                val task1 = allTasks.find { it.title == "Implement User Auth" }
                assertNotNull(task1)
                assertEquals("JWT authentication support", task1.description)
                assertEquals(TaskPriority.HIGH, task1.priority)
                assertEquals("alice", task1.assignee)
                assertEquals(setOf("backend", "priority:high"), task1.tags)
                assertEquals("myorg/myapp", task1.githubRepo)
                assertEquals("https://github.com/myorg/myapp/issues/10", task1.githubIssueUrl)
                assertEquals("TODO", task1.status)

                val task2 = allTasks.find { it.title == "Fix CSS Overflow" }
                assertNotNull(task2)
                assertEquals(TaskPriority.LOW, task2.priority)
                assertNull(task2.assignee)
                assertEquals(setOf("frontend", "priority:low"), task2.tags)
            }

        @Test
        @DisplayName("Should prevent duplicates and update existing task when syncing repeatedly")
        fun testDuplicatePreventionAndUpdate() =
            runTest {
                val initialIssue =
                    GitHubIssueDto(
                        id = 1,
                        number = 5,
                        title = "Initial Title",
                        body = "Initial Body",
                        state = "open",
                        htmlUrl = "https://github.com/org/repo/issues/5",
                        assignee = null,
                        labels = listOf(GitHubLabelDto(id = 1, name = "bug")),
                    )
                mockGitHubClient.issues.add(initialIssue)

                // First Sync -> Creates Task
                val res1 = syncService.syncRepository("org/repo")
                assertEquals(1, res1.createdCount)
                assertEquals(0, res1.updatedCount)
                assertEquals(1, kanbanService.listTasks().size)

                // Update Issue on GitHub
                mockGitHubClient.issues.clear()
                mockGitHubClient.issues.add(
                    initialIssue.copy(
                        title = "Updated Issue Title",
                        body = "Updated description body",
                        assignee = GitHubUserDto(id = 99, login = "bob"),
                        labels =
                            listOf(
                                GitHubLabelDto(id = 1, name = "bug"),
                                GitHubLabelDto(id = 2, name = "priority:urgent"),
                            ),
                    ),
                )

                // Second Sync -> Updates existing task, no duplicate!
                val res2 = syncService.syncRepository("org/repo")
                assertEquals(0, res2.createdCount)
                assertEquals(1, res2.updatedCount)
                assertEquals(1, kanbanService.listTasks().size) // Still exactly 1 task

                val updatedTask = kanbanService.listTasks().first()
                assertEquals("Updated Issue Title", updatedTask.title)
                assertEquals("Updated description body", updatedTask.description)
                assertEquals("bob", updatedTask.assignee)
                assertEquals(TaskPriority.URGENT, updatedTask.priority)
                assertTrue(updatedTask.tags.contains("priority:urgent"))
            }

        @Test
        @DisplayName("Should map priority from issue labels correctly")
        fun testPriorityLabelMapping() =
            runTest {
                mockGitHubClient.issues.addAll(
                    listOf(
                        GitHubIssueDto(
                            id = 1,
                            number = 1,
                            title = "Urgent Bug",
                            state = "open",
                            htmlUrl = "https://github.com/org/repo/issues/1",
                            labels = listOf(GitHubLabelDto(name = "p0")),
                        ),
                        GitHubIssueDto(
                            id = 2,
                            number = 2,
                            title = "High Feature",
                            state = "open",
                            htmlUrl = "https://github.com/org/repo/issues/2",
                            labels = listOf(GitHubLabelDto(name = "priority:high")),
                        ),
                        GitHubIssueDto(
                            id = 3,
                            number = 3,
                            title = "Low Chore",
                            state = "open",
                            htmlUrl = "https://github.com/org/repo/issues/3",
                            labels = listOf(GitHubLabelDto(name = "p3")),
                        ),
                        GitHubIssueDto(
                            id = 4,
                            number = 4,
                            title = "Default Medium",
                            state = "open",
                            htmlUrl = "https://github.com/org/repo/issues/4",
                            labels = listOf(GitHubLabelDto(name = "enhancement")),
                        ),
                    ),
                )

                syncService.syncRepository("org/repo")
                val tasks = kanbanService.listTasks()

                assertEquals(TaskPriority.URGENT, tasks.find { it.title == "Urgent Bug" }?.priority)
                assertEquals(TaskPriority.HIGH, tasks.find { it.title == "High Feature" }?.priority)
                assertEquals(TaskPriority.LOW, tasks.find { it.title == "Low Chore" }?.priority)
                assertEquals(TaskPriority.MEDIUM, tasks.find { it.title == "Default Medium" }?.priority)
            }

        @Test
        @DisplayName("Should sync closed issues to DONE column and set completed timestamp")
        fun testSyncClosedIssues() =
            runTest {
                mockGitHubClient.issues.add(
                    GitHubIssueDto(
                        id = 1,
                        number = 20,
                        title = "Completed Issue",
                        state = "closed",
                        htmlUrl = "https://github.com/org/repo/issues/20",
                    ),
                )

                syncService.syncRepository("org/repo", state = "closed")
                val task = kanbanService.listTasks().first()

                assertEquals("DONE", task.status)
                assertNotNull(task.completedAt)
            }

        @Test
        @DisplayName("Should exclude pull requests when includePullRequests is false")
        fun testExcludePullRequestsByDefault() =
            runTest {
                mockGitHubClient.issues.addAll(
                    listOf(
                        GitHubIssueDto(
                            id = 1,
                            number = 1,
                            title = "Pure Issue",
                            state = "open",
                            htmlUrl = "https://github.com/org/repo/issues/1",
                            pullRequest = null,
                        ),
                        GitHubIssueDto(
                            id = 2,
                            number = 2,
                            title = "Pull Request Item",
                            state = "open",
                            htmlUrl = "https://github.com/org/repo/pull/2",
                            pullRequest = GitHubPullRequestRefDto(htmlUrl = "https://github.com/org/repo/pull/2"),
                        ),
                    ),
                )

                // Exclude PRs
                val res1 = syncService.syncRepository("org/repo", includePullRequests = false)
                assertEquals(1, res1.createdCount)
                assertEquals(1, kanbanService.listTasks().size)
                assertEquals("Pure Issue", kanbanService.listTasks().first().title)

                // Include PRs
                val res2 = syncService.syncRepository("org/repo", includePullRequests = true)
                assertEquals(1, res2.createdCount)
                assertEquals(2, kanbanService.listTasks().size)
            }

        @Test
        @DisplayName("Should not modify database in dry-run mode")
        fun testDryRunMode() =
            runTest {
                mockGitHubClient.issues.add(
                    GitHubIssueDto(
                        id = 1,
                        number = 1,
                        title = "Dry Run Issue",
                        state = "open",
                        htmlUrl = "https://github.com/org/repo/issues/1",
                    ),
                )

                val result = syncService.syncRepository("org/repo", dryRun = true)

                assertEquals(1, result.totalFetched)
                assertEquals(1, result.createdCount)
                assertEquals(0, kanbanService.listTasks().size) // DB remains empty!
            }
    }

    @Nested
    @DisplayName("Single URL Sync Operations")
    inner class SingleUrlSyncTests {
        @Test
        @DisplayName("Should sync a single issue by GitHub Issue URL")
        fun testSyncSingleIssueUrl() =
            runTest {
                mockGitHubClient.issues.add(
                    GitHubIssueDto(
                        id = 1,
                        number = 6,
                        title = "feat(github): implement sync",
                        body = "Details about sync",
                        state = "open",
                        htmlUrl = "https://github.com/0oWoodenDooro0/AiKanban/issues/6",
                        labels = listOf(GitHubLabelDto(name = "priority:high")),
                    ),
                )

                val result = syncService.syncUrl("https://github.com/0oWoodenDooro0/AiKanban/issues/6")

                assertEquals(1, result.createdCount)
                assertEquals(1, kanbanService.listTasks().size)

                val created = kanbanService.listTasks().first()
                assertEquals("feat(github): implement sync", created.title)
                assertEquals("https://github.com/0oWoodenDooro0/AiKanban/issues/6", created.githubIssueUrl)
                assertEquals("0oWoodenDooro0/AiKanban", created.githubRepo)
            }
    }
}
