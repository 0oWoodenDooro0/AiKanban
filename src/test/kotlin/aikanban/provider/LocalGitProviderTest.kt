package aikanban.provider

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

class LocalGitProviderTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var service: KanbanService
    private lateinit var fakeGitRunner: FakeGitCommandRunner
    private lateinit var provider: LocalGitProvider

    class FakeGitCommandRunner : GitCommandRunner {
        var currentBranch: String = "main"
        val createdBranches = mutableListOf<Pair<String, String>>()
        val pushedBranches = mutableListOf<Pair<String, String>>()

        override fun getCurrentBranch(workingDir: File?): String = currentBranch

        override fun createAndCheckoutBranch(
            branchName: String,
            baseBranch: String,
            workingDir: File?,
        ): GitProcessResult {
            createdBranches.add(branchName to baseBranch)
            currentBranch = branchName
            return GitProcessResult(0, "Switched to a new branch '$branchName'", "")
        }

        override fun createBranchOnly(
            branchName: String,
            baseBranch: String,
            workingDir: File?,
        ): GitProcessResult {
            createdBranches.add(branchName to baseBranch)
            return GitProcessResult(0, "Created branch '$branchName'", "")
        }

        override fun pushBranch(
            branchName: String,
            remote: String,
            setUpstream: Boolean,
            workingDir: File?,
        ): GitProcessResult {
            pushedBranches.add(branchName to remote)
            return GitProcessResult(0, "Pushed branch $branchName to $remote", "")
        }

        override fun isGitRepository(workingDir: File?): Boolean = true

        override fun getRemoteUrl(
            remote: String,
            workingDir: File?,
        ): String? = null
    }

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("local_git_test.db").toFile()
        service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}"))
        fakeGitRunner = FakeGitCommandRunner()
        provider = LocalGitProvider(gitCommandRunner = fakeGitRunner, workingDir = tempDir.toFile())
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    @Test
    @DisplayName("Should resolve local issue and pull request URIs to ResolvedResource")
    fun testResolveResource() {
        val issueRes = provider.resolveResource("local://issue/LOCAL-42")
        assertNotNull(issueRes)
        assertEquals("local-git", issueRes.provider)
        assertEquals(ResourceType.ISSUE, issueRes.type)
        assertEquals(42, issueRes.number)

        val prRes = provider.resolveResource("local://pull/feature/my-branch")
        assertNotNull(prRes)
        assertEquals("local-git", prRes.provider)
        assertEquals(ResourceType.PULL_REQUEST, prRes.type)

        val invalidRes = provider.resolveResource("https://github.com/org/repo")
        assertNull(invalidRes)
    }

    @Test
    @DisplayName("Should create local issue representation without network dependency")
    fun testCreateIssue() =
        runBlocking {
            val request =
                CreateIssueRequest(
                    title = "Local Feature",
                    body = "Implement local logic",
                    labels = setOf("local", "core"),
                    priority = TaskPriority.HIGH,
                )

            val result = provider.createIssue(request)
            assertEquals("Local Feature", result.title)
            assertTrue(result.id.isNotBlank())
            assertTrue(result.id.startsWith("LOCAL-") || result.id.startsWith("#"))
            assertEquals("Implement local logic", result.body)
        }

    @Test
    @DisplayName("Should create local branch without checking it out via GitCommandRunner")
    fun testCreateBranch() =
        runBlocking {
            val request =
                CreateBranchRequest(
                    branchName = "feature/local-offline-task",
                    baseBranch = "main",
                    issueIdOrUrl = "LOCAL-1",
                )

            val result = provider.createBranch(request)
            assertEquals("feature/local-offline-task", result.branchName)
            assertEquals("main", result.baseBranch)
            assertTrue(result.created)
            assertEquals(1, fakeGitRunner.createdBranches.size)
            assertEquals("feature/local-offline-task" to "main", fakeGitRunner.createdBranches.first())
            assertEquals("main", fakeGitRunner.currentBranch)
        }

    @Test
    @DisplayName("Should create local PR review descriptor")
    fun testCreatePullRequest() =
        runBlocking {
            val request =
                CreatePullRequestRequest(
                    title = "feat(local): local feature implementation",
                    body = "PR description",
                    headBranch = "feature/local-offline-task",
                    baseBranch = "main",
                )

            val result = provider.createPullRequest(request)
            assertEquals("feat(local): local feature implementation", result.title)
            assertEquals("feature/local-offline-task", result.headBranch)
            assertEquals("main", result.baseBranch)
            assertTrue(result.url.isNotBlank())
        }

    @Test
    @DisplayName("Should sync in local mode gracefully")
    fun testLocalSync() =
        runBlocking {
            val request =
                ProviderSyncRequest(
                    state = "open",
                    dryRun = false,
                )

            val result = provider.sync(request)
            assertEquals("local-git", result.provider)
            assertNotNull(result.tasks)
        }
}
