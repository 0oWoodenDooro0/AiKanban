package aikanban.provider

import aikanban.github.client.GitHubClient
import aikanban.github.model.GitHubIssueDto
import aikanban.github.model.GitHubLabelDto
import aikanban.github.service.DefaultGitHubSyncService
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
import kotlin.test.assertTrue

class GitHubProviderTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var service: KanbanService
    private lateinit var fakeClient: FakeGitHubClient
    private lateinit var fakeGitRunner: LocalGitProviderTest.FakeGitCommandRunner
    private lateinit var provider: GitHubProvider

    class FakeGitHubClient : GitHubClient {
        val issues = mutableListOf<GitHubIssueDto>()

        override suspend fun fetchRepositoryIssues(
            owner: String,
            repo: String,
            state: String,
            labels: Set<String>,
            token: String?,
            page: Int,
            perPage: Int,
        ): List<GitHubIssueDto> = issues

        override suspend fun fetchIssue(
            owner: String,
            repo: String,
            number: Int,
            token: String?,
        ): GitHubIssueDto? = issues.find { it.number == number }

        override fun close() {}
    }

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("github_provider_test.db").toFile()
        service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}"))
        fakeClient = FakeGitHubClient()
        fakeGitRunner = LocalGitProviderTest.FakeGitCommandRunner()
        val syncService = DefaultGitHubSyncService(service, fakeClient)
        provider =
            GitHubProvider(
                kanbanService = service,
                gitHubSyncService = syncService,
                gitCommandRunner = fakeGitRunner,
                workingDir = tempDir.toFile(),
                defaultRepo = "owner/repo",
            )
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    @Test
    @DisplayName("Should sync issues from GitHub repository via GitHubSyncService")
    fun testSyncGitHubRepository() =
        runBlocking {
            fakeClient.issues.add(
                GitHubIssueDto(
                    id = 10,
                    number = 42,
                    title = "GitHub Sync Issue",
                    body = "Sync Issue Body",
                    state = "open",
                    htmlUrl = "https://github.com/owner/repo/issues/42",
                    labels = listOf(GitHubLabelDto(name = "p1")),
                ),
            )

            val syncReq =
                ProviderSyncRequest(
                    repoOrUrl = "owner/repo",
                    state = "open",
                    dryRun = false,
                )

            val result = provider.sync(syncReq)
            assertEquals("github", result.provider)
            assertEquals("owner/repo", result.repo)
            assertEquals(1, result.createdCount)
            assertEquals(1, result.tasks.size)
            assertEquals("GitHub Sync Issue", result.tasks.first().title)
            assertEquals(TaskPriority.HIGH, result.tasks.first().priority)
        }

    @Test
    @DisplayName("Should create branch and checkout for GitHub workflow")
    fun testCreateBranch() =
        runBlocking {
            val request =
                CreateBranchRequest(
                    branchName = "feature/gh-branch",
                    baseBranch = "main",
                    issueIdOrUrl = "https://github.com/owner/repo/issues/42",
                )

            val result = provider.createBranch(request)
            assertEquals("feature/gh-branch", result.branchName)
            assertEquals("main", result.baseBranch)
            assertTrue(result.created)
            assertEquals("feature/gh-branch", fakeGitRunner.currentBranch)
        }

    @Test
    @DisplayName("Should push branch and return PR result")
    fun testCreatePullRequest() =
        runBlocking {
            val request =
                CreatePullRequestRequest(
                    title = "feat(gh): gh feature",
                    body = "PR body",
                    headBranch = "feature/gh-branch",
                    baseBranch = "main",
                )

            val result = provider.createPullRequest(request)
            assertEquals("feat(gh): gh feature", result.title)
            assertEquals("feature/gh-branch", result.headBranch)
            assertEquals("main", result.baseBranch)
            assertNotNull(result.url)
        }
}
