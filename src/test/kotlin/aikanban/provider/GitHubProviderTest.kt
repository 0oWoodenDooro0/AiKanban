package aikanban.provider

import aikanban.github.client.GitHubClient
import aikanban.github.model.GitHubIssueDto
import aikanban.github.model.GitHubLabelDto
import aikanban.model.TaskPriority
import aikanban.provider.ingestion.DefaultIssueIngestionPipeline
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import aikanban.service.exception.KanbanException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private lateinit var fakeGhRunner: FakeGhCliRunner
    private lateinit var provider: GitHubProvider

    class FakeGhCliRunner : GhCliRunner {
        val executedCommands = mutableListOf<List<String>>()
        var responseHandler: (List<String>) -> GitProcessResult = {
            GitProcessResult(0, "https://github.com/owner/repo/issues/101\n", "")
        }

        override fun runGh(
            args: List<String>,
            workingDir: File,
            token: String?,
        ): GitProcessResult {
            executedCommands.add(args)
            return responseHandler(args)
        }
    }

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
        fakeGhRunner = FakeGhCliRunner()
        provider =
            GitHubProvider(
                kanbanService = service,
                gitHubClient = fakeClient,
                gitCommandRunner = fakeGitRunner,
                ghCliRunner = fakeGhRunner,
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
    @DisplayName("Should extract correct issue URL from multi-line gh stdout")
    fun testCreateIssueWithMultilineOutput() =
        runBlocking {
            fakeGhRunner.responseHandler = {
                GitProcessResult(
                    exitCode = 0,
                    stdout = "\nCreating issue in owner/repo\n\nhttps://github.com/owner/repo/issues/21\n",
                    stderr = "",
                )
            }

            val request =
                CreateIssueRequest(
                    title = "feat: new issue",
                    body = "body text",
                    labels = setOf("config"),
                    assignee = "Antigravity",
                )

            val result = provider.createIssue(request)
            assertEquals("https://github.com/owner/repo/issues/21", result.url)
            assertEquals(21, result.number)
            assertEquals("21", result.id)
            assertEquals("feat: new issue", result.title)
        }

    @Test
    @DisplayName("Should retry without label when label is invalid and succeed")
    fun testCreateIssueRetryWithoutLabelWhenLabelFails() =
        runBlocking {
            fakeGhRunner.responseHandler = { args ->
                if (args.contains("--label")) {
                    GitProcessResult(
                        exitCode = 1,
                        stdout = "Creating issue in owner/repo",
                        stderr = "could not add label: 'config' not found",
                    )
                } else {
                    GitProcessResult(
                        exitCode = 0,
                        stdout = "Creating issue in owner/repo\n\nhttps://github.com/owner/repo/issues/25\n",
                        stderr = "",
                    )
                }
            }

            val request =
                CreateIssueRequest(
                    title = "feat: retry label",
                    body = "body text",
                    labels = setOf("config"),
                )

            val result = provider.createIssue(request)
            assertEquals("https://github.com/owner/repo/issues/25", result.url)
            assertEquals(25, result.number)
        }

    @Test
    @DisplayName("Should retry without assignee when assignee is invalid and succeed")
    fun testCreateIssueRetryWithoutAssigneeWhenAssigneeFails() =
        runBlocking {
            fakeGhRunner.responseHandler = { args ->
                if (args.contains("--assignee")) {
                    GitProcessResult(
                        exitCode = 1,
                        stdout = "",
                        stderr = "GraphQL: Could not resolve to a user or bot with the login 'agent-1'.",
                    )
                } else {
                    GitProcessResult(
                        exitCode = 0,
                        stdout = "Creating issue in owner/repo\n\nhttps://github.com/owner/repo/issues/30\n",
                        stderr = "",
                    )
                }
            }

            val request =
                CreateIssueRequest(
                    title = "feat: retry assignee",
                    body = "body text",
                    assignee = "agent-1",
                )

            val result = provider.createIssue(request)
            assertEquals("https://github.com/owner/repo/issues/30", result.url)
            assertEquals(30, result.number)
        }

    @Test
    @DisplayName("Should throw KanbanException when gh issue create fails completely")
    fun testCreateIssueThrowsWhenFails() {
        fakeGhRunner.responseHandler = {
            GitProcessResult(
                exitCode = 1,
                stdout = "",
                stderr = "fatal: authentication required",
            )
        }

        val request = CreateIssueRequest(title = "fatal test")

        val exception =
            assertFailsWith<KanbanException> {
                runBlocking {
                    provider.createIssue(request)
                }
            }
        assertTrue(exception.message!!.contains("Failed to create GitHub issue"))
        assertTrue(exception.message!!.contains("authentication required"))
    }

    @Test
    @DisplayName("Should create branch and checkout via gitCommandRunner fallback when gh develop is not used")
    fun testCreateBranchGitFallback() =
        runBlocking {
            fakeGhRunner.responseHandler = {
                GitProcessResult(exitCode = 1, stdout = "", stderr = "gh develop not supported")
            }

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
    @DisplayName("Should create branch via gh issue develop when successful")
    fun testCreateBranchGhDevelop() =
        runBlocking {
            fakeGhRunner.responseHandler = {
                GitProcessResult(exitCode = 0, stdout = "Branch created and checked out", stderr = "")
            }

            val request =
                CreateBranchRequest(
                    branchName = "feature/gh-develop-branch",
                    baseBranch = "main",
                    issueIdOrUrl = "https://github.com/owner/repo/issues/42",
                )

            val result = provider.createBranch(request)
            assertEquals("feature/gh-develop-branch", result.branchName)
            assertEquals("main", result.baseBranch)
            assertTrue(result.created)
            assertEquals("https://github.com/owner/repo/issues/42", result.linkedIssueUrl)
        }

    @Test
    @DisplayName("Should push branch and return PR result with extracted URL from multi-line output")
    fun testCreatePullRequest() =
        runBlocking {
            fakeGhRunner.responseHandler = {
                GitProcessResult(
                    exitCode = 0,
                    stdout =
                        "Creating pull request for feature/gh-branch into main in owner/repo\n\n" +
                            "https://github.com/owner/repo/pull/77\n",
                    stderr = "",
                )
            }

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
            assertEquals("https://github.com/owner/repo/pull/77", result.url)
            assertEquals(77, result.number)
        }

    @Test
    @DisplayName("Should throw KanbanException when gh pr create fails")
    fun testCreatePullRequestThrowsWhenFails() {
        fakeGhRunner.responseHandler = {
            GitProcessResult(
                exitCode = 1,
                stdout = "",
                stderr = "GraphQL: A pull request already exists for this branch",
            )
        }

        val request =
            CreatePullRequestRequest(
                title = "feat(gh): duplicate pr",
                headBranch = "feature/dup",
            )

        val exception =
            assertFailsWith<KanbanException> {
                runBlocking {
                    provider.createPullRequest(request)
                }
            }
        assertTrue(exception.message!!.contains("Failed to create GitHub pull request"))
        assertTrue(exception.message!!.contains("already exists"))
    }
}
