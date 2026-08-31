package aikanban.workflow

import aikanban.config.AiKanbanConfig
import aikanban.model.TaskPriority
import aikanban.provider.LocalGitProviderTest
import aikanban.provider.ProviderFactory
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

class KanbanWorkflowServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var service: KanbanService
    private lateinit var fakeGitRunner: LocalGitProviderTest.FakeGitCommandRunner
    private lateinit var providerFactory: ProviderFactory
    private lateinit var workflowService: DefaultKanbanWorkflowService

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("workflow_test.db").toFile()
        service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}"))
        fakeGitRunner = LocalGitProviderTest.FakeGitCommandRunner()
        providerFactory =
            ProviderFactory(
                kanbanService = service,
                gitCommandRunner = fakeGitRunner,
                workingDir = tempDir.toFile(),
            )
        workflowService =
            DefaultKanbanWorkflowService(
                kanbanService = service,
                providerFactory = providerFactory,
                config = AiKanbanConfig(provider = "local-git", defaultBaseBranch = "main", branchPrefix = "feature/"),
                gitCommandRunner = fakeGitRunner,
            )
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    @Test
    @DisplayName("Should execute startIssue workflow: issue creation, kanban task, plan comment, branch checkout, logs")
    fun testStartIssueFullWorkflow() =
        runBlocking {
            val request =
                StartIssueRequest(
                    title = "Implement Pluggable Providers",
                    description = "Add LocalGit and GitHub provider support",
                    priority = TaskPriority.HIGH,
                    tags = setOf("provider", "vcs"),
                    branchName = "feature/provider-arch",
                    baseBranch = "main",
                    plan = "## Plan\n1. Define interface\n2. Implement classes",
                    assignee = "agent-1",
                    operator = "agent-1",
                )

            val result = workflowService.startIssue(request)

            // 1. Task created in TODO
            assertEquals("Implement Pluggable Providers", result.task.title)
            assertEquals(TaskPriority.HIGH, result.task.priority)
            assertEquals("agent-1", result.task.assignee)
            assertEquals(setOf("provider", "vcs"), result.task.tags)
            assertEquals("TODO", result.task.status)

            // 2. Issue result
            assertNotNull(result.issue)
            assertEquals("Implement Pluggable Providers", result.issue.title)

            // 3. Branch created and checked out
            assertNotNull(result.branch)
            assertEquals("feature/provider-arch", result.branch.branchName)
            assertEquals("feature/provider-arch", fakeGitRunner.currentBranch)

            // 4. Verify Kanban audit logs contain creation, plan, and branch logs
            val taskInDb = service.getTask(result.task.id)
            assertTrue(taskInDb.logs.any { it.comment.contains("Task created") })
            assertTrue(taskInDb.logs.any { it.comment.contains("Attached implementation plan") })
            assertTrue(taskInDb.logs.any { it.comment.contains("Created and switched to branch") })
        }

    @Test
    @DisplayName("Should auto-generate clean branch name from title if branch is omitted in startIssue")
    fun testStartIssueAutoGenerateBranch() =
        runBlocking {
            val request =
                StartIssueRequest(
                    title = "feat(auth): Add JWT Token Refresh Endpoint",
                    baseBranch = "main",
                )

            val result = workflowService.startIssue(request)
            assertEquals("feature/feat-auth-add-jwt-token-refresh-endpoint", result.branch.branchName)
            assertEquals("feature/feat-auth-add-jwt-token-refresh-endpoint", fakeGitRunner.currentBranch)
        }

    @Test
    @DisplayName("Should support dry-run in startIssue without writing to DB or git")
    fun testStartIssueDryRun() =
        runBlocking {
            val request =
                StartIssueRequest(
                    title = "Dry Run Feature",
                    dryRun = true,
                )

            val result = workflowService.startIssue(request)
            assertEquals("Dry Run Feature", result.task.title)
            assertEquals(0, service.listTasks().size)
            assertEquals(0, fakeGitRunner.createdBranches.size)
        }

    @Test
    @DisplayName("Should execute submitPr workflow: branch push, pr creation, pr URL link, transition to REVIEW")
    fun testSubmitPrWorkflow() =
        runBlocking {
            // Create initial task in IN_PROGRESS
            val task =
                service.createTask(
                    title = "Feature To Submit",
                    status = "IN_PROGRESS",
                    assignee = "agent-1",
                )
            fakeGitRunner.currentBranch = "feature/feature-to-submit"

            val request =
                SubmitPrRequest(
                    taskId = task.id,
                    title = "feat: implement feature to submit",
                    body = "## Summary\nImplemented feature",
                    headBranch = "feature/feature-to-submit",
                    baseBranch = "main",
                    operator = "agent-1",
                )

            val result = workflowService.submitPr(request)

            // 1. Verify PR creation result
            assertEquals("feat: implement feature to submit", result.pr.title)
            assertEquals("feature/feature-to-submit", result.pr.headBranch)
            assertNotNull(result.pr.url)

            // 2. Verify git push was called
            assertEquals(1, fakeGitRunner.pushedBranches.size)
            assertEquals("feature/feature-to-submit" to "origin", fakeGitRunner.pushedBranches.first())

            // 3. Verify task was moved to REVIEW and linked PR URL
            val updatedTask = service.getTask(task.id)
            assertEquals("REVIEW", updatedTask.status)
            assertEquals(result.pr.url, updatedTask.githubPrUrl)
            assertTrue(updatedTask.logs.any { it.toStatus == "REVIEW" && it.prUrl == result.pr.url })
        }

    @Test
    @DisplayName("Should support dry-run in submitPr without mutating task or pushing git")
    fun testSubmitPrDryRun() =
        runBlocking {
            val task = service.createTask(title = "Dry Run PR Task", status = "IN_PROGRESS")
            fakeGitRunner.currentBranch = "feature/dry-run"

            val request =
                SubmitPrRequest(
                    taskId = task.id,
                    headBranch = "feature/dry-run",
                    dryRun = true,
                )

            val result = workflowService.submitPr(request)
            assertEquals(task.id, result.task.id)
            assertEquals(0, fakeGitRunner.pushedBranches.size)

            val taskInDb = service.getTask(task.id)
            assertEquals("IN_PROGRESS", taskInDb.status) // unchanged
        }

    @Test
    @DisplayName("Should persist structured branch name in Task when executing startIssue")
    fun testStartIssuePersistsTaskBranch() =
        runBlocking {
            val request =
                StartIssueRequest(
                    title = "Structured Branch Feature",
                    branchName = "feature/structured-branch-feat",
                    baseBranch = "main",
                )

            val result = workflowService.startIssue(request)
            assertEquals("feature/structured-branch-feat", result.task.branch)

            val persisted = service.getTask(result.task.id)
            assertEquals("feature/structured-branch-feat", persisted.branch)
        }

    @Test
    @DisplayName("Should automatically checkout task branch when executing startTask")
    fun testStartTaskAutoCheckoutBranch() =
        runBlocking {
            val task =
                service.createTask(
                    title = "Task To Start and Checkout",
                    status = "TODO",
                    branch = "feature/task-auto-checkout",
                )
            fakeGitRunner.currentBranch = "main"

            val started =
                workflowService.startTask(
                    StartTaskRequest(
                        taskId = task.id,
                        assignee = "agent-dev",
                        checkoutBranch = true,
                    ),
                    workingDir = tempDir.toFile(),
                )

            assertEquals("IN_PROGRESS", started.status)
            assertEquals("agent-dev", started.assignee)
            assertEquals("feature/task-auto-checkout", fakeGitRunner.currentBranch)
        }

    @Test
    @DisplayName("Should skip checkout when startTask is called with checkoutBranch = false")
    fun testStartTaskNoCheckout() =
        runBlocking {
            val task =
                service.createTask(
                    title = "Task To Start Without Checkout",
                    status = "TODO",
                    branch = "feature/skip-checkout-branch",
                )
            fakeGitRunner.currentBranch = "main"

            val started =
                workflowService.startTask(
                    StartTaskRequest(
                        taskId = task.id,
                        assignee = "agent-dev",
                        checkoutBranch = false,
                    ),
                    workingDir = tempDir.toFile(),
                )

            assertEquals("IN_PROGRESS", started.status)
            assertEquals("main", fakeGitRunner.currentBranch) // Remains on main
        }
}
