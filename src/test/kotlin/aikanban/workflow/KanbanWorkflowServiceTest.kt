package aikanban.workflow

import aikanban.config.AiKanbanConfig
import aikanban.model.Task
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
    @DisplayName("Should execute startIssue workflow: issue creation, kanban task, plan comment, branch creation, logs")
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

            // 3. Branch created without checkout
            assertNotNull(result.branch)
            assertEquals("feature/provider-arch", result.branch.branchName)
            assertEquals("main", fakeGitRunner.currentBranch)

            // 4. Verify Kanban audit logs contain creation, plan, and branch logs
            val taskInDb = service.getTask(result.task.id)
            assertTrue(taskInDb.logs.any { it.comment.contains("Task created") })
            assertTrue(taskInDb.logs.any { it.comment.contains("Attached implementation plan") })
            assertTrue(taskInDb.logs.any { it.comment.contains("Created branch feature/provider-arch") })
        }

    @Test
    @DisplayName("Should auto-generate clean branch name from title without checkout in startIssue")
    fun testStartIssueAutoGenerateBranch() =
        runBlocking {
            val request =
                StartIssueRequest(
                    title = "feat(auth): Add JWT Token Refresh Endpoint",
                    baseBranch = "main",
                )

            val result = workflowService.startIssue(request)
            assertEquals("feature/feat-auth-add-jwt-token-refresh-endpoint", result.branch.branchName)
            assertEquals("main", fakeGitRunner.currentBranch)
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

    @Test
    @DisplayName("Should correctly extract issue number from various URL and ID formats")
    fun testExtractIssueNumber() {
        assertEquals(39, DefaultKanbanWorkflowService.extractIssueNumber("https://github.com/0oWoodenDooro0/AiKanban/issues/39"))
        assertEquals(100, DefaultKanbanWorkflowService.extractIssueNumber("https://github.com/owner/repo/issues/100/"))
        assertEquals(42, DefaultKanbanWorkflowService.extractIssueNumber("https://gitlab.com/group/repo/-/issues/42"))
        assertEquals(7, DefaultKanbanWorkflowService.extractIssueNumber("local://issue/LOCAL-7"))
        assertEquals(15, DefaultKanbanWorkflowService.extractIssueNumber("local://issue/15"))
        assertEquals(88, DefaultKanbanWorkflowService.extractIssueNumber("#88"))
        assertEquals(99, DefaultKanbanWorkflowService.extractIssueNumber("99"))
        kotlin.test.assertNull(DefaultKanbanWorkflowService.extractIssueNumber(null))
        kotlin.test.assertNull(DefaultKanbanWorkflowService.extractIssueNumber(""))
        kotlin.test.assertNull(DefaultKanbanWorkflowService.extractIssueNumber("   "))
        kotlin.test.assertNull(DefaultKanbanWorkflowService.extractIssueNumber("https://github.com/owner/repo"))
    }

    @Test
    @DisplayName("Should correctly extract Markdown checklist items from text")
    fun testExtractChecklist() {
        val markdown =
            """
            ## Summary
            Some text
            
            ## Tasks
            - [ ] Task 1: Initialize module
            - [x] Task 2: Write tests
            * [ ] Task 3: Implement feature
            1. [X] Task 4: Complete docs
            
            Regular list:
            - Just an item
            * Another item
            """.trimIndent()

        val checklist = DefaultKanbanWorkflowService.extractChecklist(markdown)
        assertEquals(4, checklist.size)
        assertEquals("- [ ] Task 1: Initialize module", checklist[0])
        assertEquals("- [x] Task 2: Write tests", checklist[1])
        assertEquals("* [ ] Task 3: Implement feature", checklist[2])
        assertEquals("1. [X] Task 4: Complete docs", checklist[3])

        assertTrue(DefaultKanbanWorkflowService.extractChecklist("No checklist here").isEmpty())
    }

    @Test
    @DisplayName("Should build PR body falling back to task description and appending Closes #N when request body is null")
    fun testBuildPrBodyWithNullBodyAndTaskDescription() {
        val task =
            Task(
                id = 1,
                title = "Feature Task",
                description = "## Summary\nImplemented feature\n\n## Tasks\n- [x] Step 1",
                githubIssueUrl = "https://github.com/0oWoodenDooro0/AiKanban/issues/39",
            )

        val rendered = DefaultKanbanWorkflowService.buildPrBody(null, task)
        assertTrue(rendered.contains("## Summary\nImplemented feature"))
        assertTrue(rendered.contains("- [x] Step 1"))
        assertTrue(rendered.endsWith("Closes #39"))
    }

    @Test
    @DisplayName("Should build PR body extracting checklist from description and appending Closes #N when custom body lacks checklist")
    fun testBuildPrBodyWithCustomBodyAndTaskDescriptionChecklist() {
        val task =
            Task(
                id = 2,
                title = "Feature Task",
                description = "## Summary\nOriginal task\n\n## Tasks\n- [ ] Task 1\n- [x] Task 2",
                githubIssueUrl = "https://github.com/0oWoodenDooro0/AiKanban/issues/39",
            )

        val rendered = DefaultKanbanWorkflowService.buildPrBody("## Custom PR Body\nFeature ready for review", task)
        assertTrue(rendered.contains("## Custom PR Body\nFeature ready for review"))
        assertTrue(rendered.contains("## Checklist"))
        assertTrue(rendered.contains("- [ ] Task 1"))
        assertTrue(rendered.contains("- [x] Task 2"))
        assertTrue(rendered.endsWith("Closes #39"))
    }

    @Test
    @DisplayName("Should not duplicate Closes #N if PR body or description already contains Closes, Resolves, or Fixes keyword")
    fun testBuildPrBodyDoesNotDuplicateExistingClosesOrResolves() {
        val task =
            Task(
                id = 3,
                title = "Fix bug",
                githubIssueUrl = "https://github.com/0oWoodenDooro0/AiKanban/issues/39",
            )

        val withCloses = DefaultKanbanWorkflowService.buildPrBody("## Summary\nFixed bug\n\nCloses #39", task)
        assertEquals("## Summary\nFixed bug\n\nCloses #39", withCloses.trim())

        val withResolves = DefaultKanbanWorkflowService.buildPrBody("## Summary\nFixed bug\n\nResolves #39", task)
        assertEquals("## Summary\nFixed bug\n\nResolves #39", withResolves.trim())

        val withFixes = DefaultKanbanWorkflowService.buildPrBody("## Summary\nFixed bug\n\nFixes #39", task)
        assertEquals("## Summary\nFixed bug\n\nFixes #39", withFixes.trim())
    }

    @Test
    @DisplayName("Should not append Closes #N when task has no githubIssueUrl")
    fun testBuildPrBodyWithoutIssueUrl() {
        val task =
            Task(
                id = 4,
                title = "Offline Feature",
                description = "Offline task description",
                githubIssueUrl = null,
            )

        val rendered = DefaultKanbanWorkflowService.buildPrBody("Custom PR Body", task)
        assertEquals("Custom PR Body", rendered)
    }

    @Test
    @DisplayName("Should submit PR end-to-end with auto-linked issue number and checklist attached to PR request")
    fun testSubmitPrEndToEndWithIssueLinkingAndChecklist() =
        runBlocking {
            val localProvider = aikanban.provider.LocalGitProvider(gitCommandRunner = fakeGitRunner, workingDir = tempDir.toFile())
            var capturedRequest: aikanban.provider.CreatePullRequestRequest? = null
            val recordingProvider =
                object : aikanban.provider.IssueTrackerProvider by localProvider {
                    override suspend fun createPullRequest(
                        request: aikanban.provider.CreatePullRequestRequest,
                    ): aikanban.provider.PullRequestResult {
                        capturedRequest = request
                        return localProvider.createPullRequest(request)
                    }
                }

            val workflowWithRecording =
                DefaultKanbanWorkflowService(
                    kanbanService = service,
                    providerFactory = providerFactory,
                    config = AiKanbanConfig(provider = "local-git", defaultBaseBranch = "main"),
                    gitCommandRunner = fakeGitRunner,
                    providerOverride = recordingProvider,
                )

            val task =
                service.createTask(
                    title = "End To End PR Task",
                    description = "## Description\nTask details\n\n- [ ] Subtask 1\n- [x] Subtask 2",
                    githubIssueUrl = "https://github.com/0oWoodenDooro0/AiKanban/issues/39",
                    status = "IN_PROGRESS",
                    assignee = "agent-1",
                )
            fakeGitRunner.currentBranch = "feature/e2e-pr-test"

            val request =
                SubmitPrRequest(
                    taskId = task.id,
                    title = "feat: submit pr with issue link",
                    body = "## Summary\nImplemented e2e feature",
                    headBranch = "feature/e2e-pr-test",
                    baseBranch = "main",
                )

            val result = workflowWithRecording.submitPr(request)

            assertNotNull(capturedRequest)
            assertTrue(capturedRequest!!.body.contains("## Summary\nImplemented e2e feature"))
            assertTrue(capturedRequest!!.body.contains("## Checklist"))
            assertTrue(capturedRequest!!.body.contains("- [ ] Subtask 1"))
            assertTrue(capturedRequest!!.body.contains("- [x] Subtask 2"))
            assertTrue(capturedRequest!!.body.endsWith("Closes #39"))

            val updatedTask = service.getTask(task.id)
            assertEquals("REVIEW", updatedTask.status)
            assertEquals(result.pr.url, updatedTask.githubPrUrl)
        }
}
