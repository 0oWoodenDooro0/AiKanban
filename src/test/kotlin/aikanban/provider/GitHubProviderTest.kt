package aikanban.provider

import aikanban.github.client.GitHubClient
import aikanban.github.model.GitHubIssueDto
import aikanban.github.model.GitHubLabelDto
import aikanban.model.TaskPriority
import aikanban.provider.ingestion.DefaultIssueIngestionPipeline
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
        provider =
            GitHubProvider(
                kanbanService = service,
                gitHubClient = fakeClient,
                gitCommandRunner = fakeGitRunner,
                workingDir = tempDir.toFile(),
                defaultRepo = "owner/repo",
                ingestionPipeline = DefaultIssueIngestionPipeline(service),
            )
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    @Test
    @DisplayName("Should resolve GitHub URLs to canonical ResolvedResource metadata")
    fun testResolveResource() {
        val issueRes = provider.resolveResource("https://github.com/0oWoodenDooro0/AiKanban/issues/6")
        assertNotNull(issueRes)
        assertEquals("github", issueRes.provider)
        assertEquals("0oWoodenDooro0", issueRes.owner)
        assertEquals("AiKanban", issueRes.repo)
        assertEquals(ResourceType.ISSUE, issueRes.type)
        assertEquals(6, issueRes.number)

        val prRes = provider.resolveResource("https://github.com/0oWoodenDooro0/AiKanban/pull/12")
        assertNotNull(prRes)
        assertEquals(ResourceType.PULL_REQUEST, prRes.type)
        assertEquals(12, prRes.number)

        val repoRes = provider.resolveResource("https://github.com/0oWoodenDooro0/AiKanban")
        assertNotNull(repoRes)
        assertEquals(ResourceType.REPOSITORY, repoRes.type)
        assertNull(repoRes.number)

        val invalidRes = provider.resolveResource("not-a-valid-url")
        assertNull(invalidRes)
    }

    @Test
    @DisplayName("Should sync issues from GitHub repository via direct ingestion")
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
    @DisplayName("Should sync a single issue when URL is provided in sync request")
    fun testSyncSingleIssueUrl() =
        runBlocking {
            fakeClient.issues.add(
                GitHubIssueDto(
                    id = 12,
                    number = 99,
                    title = "Single Targeted Issue",
                    body = "Details",
                    state = "open",
                    htmlUrl = "https://github.com/owner/repo/issues/99",
                ),
            )

            val syncReq =
                ProviderSyncRequest(
                    repoOrUrl = "https://github.com/owner/repo/issues/99",
                    dryRun = false,
                )

            val result = provider.sync(syncReq)
            assertEquals("github", result.provider)
            assertEquals(1, result.createdCount)
            assertEquals("Single Targeted Issue", result.tasks.first().title)
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
