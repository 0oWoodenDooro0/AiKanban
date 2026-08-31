package aikanban.workflow

import aikanban.config.AiKanbanConfig
import aikanban.config.AiKanbanConfigLoader
import aikanban.config.WorkflowOptionsConfig
import aikanban.provider.AddIssueCommentRequest
import aikanban.provider.ApprovePullRequestRequest
import aikanban.provider.BranchResult
import aikanban.provider.CreateBranchRequest
import aikanban.provider.CreateIssueRequest
import aikanban.provider.CreatePullRequestRequest
import aikanban.provider.GitCommandRunner
import aikanban.provider.GitProcessResult
import aikanban.provider.IssueResult
import aikanban.provider.IssueTrackerProvider
import aikanban.provider.MergePullRequestRequest
import aikanban.provider.ProviderFactory
import aikanban.provider.ProviderSyncRequest
import aikanban.provider.ProviderSyncResult
import aikanban.provider.PullRequestResult
import aikanban.provider.RequestChangesPullRequestRequest
import aikanban.provider.ResolvedResource
import aikanban.provider.UpdateIssueRequest
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import aikanban.service.exception.KanbanException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowReviewTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var service: KanbanService
    private lateinit var fakeGitRunner: TestGitCommandRunner
    private lateinit var fakeShellRunner: TestShellCommandRunner
    private lateinit var fakeProvider: TestIssueTrackerProvider
    private lateinit var providerFactory: ProviderFactory

    class TestGitCommandRunner : GitCommandRunner {
        var currentBranch: String = "main"
        val checkedOutBranches = mutableListOf<String>()
        val createdBranches = mutableListOf<Pair<String, String>>()
        val pushedBranches = mutableListOf<Pair<String, String>>()
        val deletedBranches = mutableListOf<Pair<String, Boolean>>()
        val mergedBranches = mutableListOf<Pair<String, Boolean>>()

        override fun getCurrentBranch(workingDir: File?): String = currentBranch

        override fun createAndCheckoutBranch(
            branchName: String,
            baseBranch: String,
            workingDir: File?,
        ): GitProcessResult {
            createdBranches.add(branchName to baseBranch)
            currentBranch = branchName
            return GitProcessResult(0, "Switched to branch $branchName", "")
        }

        override fun checkoutBranch(
            branchName: String,
            createIfMissing: Boolean,
            baseBranch: String?,
            workingDir: File?,
        ): GitProcessResult {
            checkedOutBranches.add(branchName)
            currentBranch = branchName
            return GitProcessResult(0, "Switched to branch $branchName", "")
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

        override fun deleteBranch(
            branchName: String,
            force: Boolean,
            remote: Boolean,
            remoteName: String,
            workingDir: File?,
        ): GitProcessResult {
            deletedBranches.add(branchName to remote)
            return GitProcessResult(0, "Deleted branch $branchName", "")
        }

        override fun mergeBranch(
            branchName: String,
            squash: Boolean,
            workingDir: File?,
        ): GitProcessResult {
            mergedBranches.add(branchName to squash)
            return GitProcessResult(0, "Merged branch $branchName", "")
        }

        override fun isGitRepository(workingDir: File?): Boolean = true

        override fun getRemoteUrl(
            remote: String,
            workingDir: File?,
        ): String? = "https://github.com/owner/repo.git"
    }

    class TestShellCommandRunner : ShellCommandRunner {
        val executedCommands = mutableListOf<String>()
        var commandResultFactory: (String) -> CommandExecutionResult = { cmd ->
            CommandExecutionResult(
                command = cmd,
                exitCode = 0,
                stdout = "output of $cmd",
                stderr = "",
                success = true,
            )
        }

        override fun execute(
            command: String,
            workingDir: File,
        ): CommandExecutionResult {
            executedCommands.add(command)
            return commandResultFactory(command)
        }
    }

    class TestIssueTrackerProvider(override val name: String = "test-provider") : IssueTrackerProvider {
        val createdIssues = mutableListOf<CreateIssueRequest>()
        val updatedIssues = mutableListOf<UpdateIssueRequest>()
        val approvedPrs = mutableListOf<ApprovePullRequestRequest>()
        val changeRequests = mutableListOf<RequestChangesPullRequestRequest>()
        val mergedPrs = mutableListOf<MergePullRequestRequest>()

        override fun resolveResource(url: String): ResolvedResource? = null

        override suspend fun createIssue(request: CreateIssueRequest): IssueResult {
            createdIssues.add(request)
            return IssueResult("100", 100, request.title, "https://github.com/owner/repo/issues/100", request.body)
        }

        override suspend fun updateIssue(request: UpdateIssueRequest): IssueResult {
            updatedIssues.add(request)
            return IssueResult("100", 100, request.title ?: "Title", request.issueIdOrUrl, request.body)
        }

        override suspend fun addComment(request: AddIssueCommentRequest): Boolean = true

        override suspend fun createBranch(request: CreateBranchRequest): BranchResult {
            return BranchResult(request.branchName, request.baseBranch, true, request.issueIdOrUrl)
        }

        override suspend fun createPullRequest(request: CreatePullRequestRequest): PullRequestResult {
            return PullRequestResult(
                "https://github.com/owner/repo/pull/100",
                100,
                request.title,
                request.headBranch,
                request.baseBranch,
                request.draft,
            )
        }

        override suspend fun approvePullRequest(request: ApprovePullRequestRequest): Boolean {
            approvedPrs.add(request)
            return true
        }

        override suspend fun requestChangesPullRequest(request: RequestChangesPullRequestRequest): Boolean {
            changeRequests.add(request)
            return true
        }

        override suspend fun mergePullRequest(request: MergePullRequestRequest): Boolean {
            mergedPrs.add(request)
            return true
        }

        override suspend fun sync(request: ProviderSyncRequest): ProviderSyncResult {
            return ProviderSyncResult(provider = name, repo = "owner/repo")
        }
    }

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("review_test.db").toFile()
        service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}"))
        fakeGitRunner = TestGitCommandRunner()
        fakeShellRunner = TestShellCommandRunner()
        fakeProvider = TestIssueTrackerProvider()
        providerFactory =
            ProviderFactory(
                kanbanService = service,
                gitCommandRunner = fakeGitRunner,
                workingDir = tempDir.toFile(),
            )
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    private fun createWorkflowService(config: AiKanbanConfig = AiKanbanConfig()): DefaultKanbanWorkflowService {
        return DefaultKanbanWorkflowService(
            kanbanService = service,
            providerFactory = providerFactory,
            config = config,
            gitCommandRunner = fakeGitRunner,
            shellCommandRunner = fakeShellRunner,
            providerOverride = fakeProvider,
        )
    }

    @Nested
    @DisplayName("start-review Workflow Tests")
    inner class StartReviewTests {
        @Test
        @DisplayName("Should start review for specific taskId, checkout branch, run hooks, and add log")
        fun testStartReviewWithTaskId() =
            runBlocking {
                val task =
                    service.createTask(
                        title = "feat: add user login",
                        status = "REVIEW",
                        githubIssueUrl = "https://github.com/owner/repo/issues/10",
                    )
                service.moveTask(task.id, "REVIEW", "author", "Ready for review", prUrl = "https://github.com/owner/repo/pull/55")
                service.addComment(task.id, "author", "Created and switched to branch feature/user-login")

                val config =
                    AiKanbanConfig(
                        hooks =
                            mapOf(
                                "pre-start-review" to listOf("echo pre-review"),
                                "post-start-review" to listOf("echo post-review"),
                            ),
                    )
                val workflowService = createWorkflowService(config)

                val result =
                    workflowService.startReview(
                        StartReviewRequest(
                            taskId = task.id,
                            operator = "reviewer",
                        ),
                    )

                assertEquals(task.id, result.task.id)
                assertEquals("feature/user-login", result.branchName)
                assertEquals("feature/user-login", fakeGitRunner.currentBranch)
                assertEquals(listOf("feature/user-login"), fakeGitRunner.checkedOutBranches)
                assertEquals(listOf("echo pre-review", "echo post-review"), fakeShellRunner.executedCommands)

                val updated = service.getTask(task.id)
                assertTrue(updated.logs.any { it.operator == "reviewer" && it.comment.contains("Started code review") })
            }

        @Test
        @DisplayName("Should auto-pick first task in REVIEW column when taskId is omitted")
        fun testStartReviewAutoPickFirstReviewTask() =
            runBlocking {
                service.createTask(title = "Task in TODO", status = "TODO")
                val reviewTask = service.createTask(title = "Top Review Task", status = "REVIEW")
                service.createTask(title = "Second Review Task", status = "REVIEW")
                service.addComment(reviewTask.id, "author", "Created and switched to branch feature/top-task")

                val workflowService = createWorkflowService()
                val result = workflowService.startReview(StartReviewRequest(taskId = null, operator = "reviewer"))

                assertEquals(reviewTask.id, result.task.id)
                assertEquals("feature/top-task", result.branchName)
            }

        @Test
        @DisplayName("Should throw KanbanException when no tasks exist in REVIEW column")
        fun testStartReviewNoTasksInReview() =
            runBlocking {
                service.createTask(title = "Task in TODO", status = "TODO")
                val workflowService = createWorkflowService()

                assertFailsWith<KanbanException> {
                    workflowService.startReview(StartReviewRequest(taskId = null))
                }
            }

        @Test
        @DisplayName("Should abort start-review if pre-start-review hook fails")
        fun testStartReviewPreHookFails() =
            runBlocking {
                val task = service.createTask(title = "Review Task", status = "REVIEW")
                val config =
                    AiKanbanConfig(
                        hooks = mapOf("pre-start-review" to listOf("echo fail-hook")),
                    )
                fakeShellRunner.commandResultFactory = { cmd ->
                    CommandExecutionResult(cmd, exitCode = 1, stdout = "", stderr = "Pre-review hook failed", success = false)
                }
                val workflowService = createWorkflowService(config)

                assertFailsWith<IllegalStateException> {
                    workflowService.startReview(StartReviewRequest(taskId = task.id))
                }
            }
    }

    @Nested
    @DisplayName("request-changes Workflow Tests")
    inner class RequestChangesTests {
        @Test
        @DisplayName("Should move task to REQUEST column, add comment, and submit change request to provider PR")
        fun testRequestChangesSuccess() =
            runBlocking {
                val task = service.createTask(title = "Task to Request Changes", status = "REVIEW")
                service.moveTask(task.id, "REVIEW", "author", "PR created", prUrl = "https://github.com/owner/repo/pull/77")

                val workflowService = createWorkflowService()
                val result =
                    workflowService.requestChanges(
                        RequestChangesWorkflowRequest(
                            taskId = task.id,
                            comment = "Please add unit tests for error handling",
                            operator = "reviewer",
                        ),
                    )

                assertEquals("REQUEST", result.task.status)
                assertEquals(1, fakeProvider.changeRequests.size)
                assertEquals("https://github.com/owner/repo/pull/77", fakeProvider.changeRequests.first().prNumberOrUrl)
                assertEquals("Please add unit tests for error handling", fakeProvider.changeRequests.first().comment)

                val updated = service.getTask(task.id)
                assertEquals("REQUEST", updated.status)
                assertTrue(updated.logs.any { it.toStatus == "REQUEST" && it.comment.contains("Please add unit tests") })
            }

        @Test
        @DisplayName("Should respect custom requestColumn in WorkflowOptionsConfig")
        fun testRequestChangesCustomColumn() =
            runBlocking {
                val task = service.createTask(title = "Task to Request Changes", status = "REVIEW")
                val config =
                    AiKanbanConfig(
                        workflow = WorkflowOptionsConfig(requestColumn = "REOPEN"),
                    )
                val workflowService = createWorkflowService(config)
                val result =
                    workflowService.requestChanges(
                        RequestChangesWorkflowRequest(
                            taskId = task.id,
                            comment = "Reopen for fixes",
                            operator = "reviewer",
                        ),
                    )

                assertEquals("REOPEN", result.task.status)
            }
    }

    @Nested
    @DisplayName("complete-review Workflow Tests")
    inner class CompleteReviewTests {
        @Test
        @DisplayName("Should complete review without merge, execute hooks, and transition to DONE")
        fun testCompleteReviewWithoutMerge() =
            runBlocking {
                val task = service.createTask(title = "Task to Complete", status = "REVIEW")
                val config =
                    AiKanbanConfig(
                        hooks =
                            mapOf(
                                "pre-complete-review" to listOf("echo pre-complete"),
                                "post-complete-review" to listOf("echo post-complete"),
                            ),
                    )
                val workflowService = createWorkflowService(config)

                val result =
                    workflowService.completeReview(
                        CompleteReviewWorkflowRequest(
                            taskId = task.id,
                            merge = false,
                            comment = "LGTM approved",
                            operator = "reviewer",
                        ),
                    )

                assertEquals("DONE", result.task.status)
                assertFalse(result.merged)
                assertEquals(0, fakeProvider.mergedPrs.size)
                assertEquals(listOf("echo pre-complete", "echo post-complete"), fakeShellRunner.executedCommands)

                val updated = service.getTask(task.id)
                assertEquals("DONE", updated.status)
                assertTrue(updated.logs.any { it.toStatus == "DONE" && it.comment.contains("LGTM approved") })
            }

        @Test
        @DisplayName(
            "Should complete review with merge, call provider mergePullRequest with configured mergeMethod, and transition to DONE",
        )
        fun testCompleteReviewWithMerge() =
            runBlocking {
                val task = service.createTask(title = "Task to Merge", status = "REVIEW")
                service.moveTask(task.id, "REVIEW", "author", "PR ready", prUrl = "https://github.com/owner/repo/pull/99")

                val config =
                    AiKanbanConfig(
                        workflow =
                            WorkflowOptionsConfig(
                                mergeMethod = "squash",
                                deleteBranchOnMerge = true,
                                doneColumn = "DONE",
                            ),
                    )
                val workflowService = createWorkflowService(config)

                val result =
                    workflowService.completeReview(
                        CompleteReviewWorkflowRequest(
                            taskId = task.id,
                            merge = true,
                            operator = "reviewer",
                        ),
                    )

                assertEquals("DONE", result.task.status)
                assertTrue(result.merged)
                assertEquals(1, fakeProvider.mergedPrs.size)
                val mergedPr = fakeProvider.mergedPrs.first()
                assertEquals("https://github.com/owner/repo/pull/99", mergedPr.prNumberOrUrl)
                assertEquals("squash", mergedPr.mergeMethod)
                assertTrue(mergedPr.deleteBranch)
            }
    }

    @Nested
    @DisplayName("start-issue Enhancements Tests")
    inner class StartIssueEnhancementsTests {
        @Test
        @DisplayName("Should push branch to remote when pushBranch is true")
        fun testStartIssueWithPush() =
            runBlocking {
                val workflowService = createWorkflowService()
                val request =
                    StartIssueRequest(
                        title = "feat: push branch task",
                        branchName = "feature/push-task",
                        pushBranch = true,
                    )

                val result = workflowService.startIssue(request)
                assertEquals("feature/push-task", result.branch.branchName)
                assertEquals(1, fakeGitRunner.pushedBranches.size)
                assertEquals("feature/push-task" to "origin", fakeGitRunner.pushedBranches.first())
            }

        @Test
        @DisplayName("Should restore original branch when noCheckout is true")
        fun testStartIssueWithNoCheckout() =
            runBlocking {
                fakeGitRunner.currentBranch = "main"
                val workflowService = createWorkflowService()
                val request =
                    StartIssueRequest(
                        title = "feat: background task branch",
                        branchName = "feature/bg-branch",
                        noCheckout = true,
                    )

                val result = workflowService.startIssue(request)
                assertEquals("feature/bg-branch", result.branch.branchName)
                // Working directory branch must remain on 'main'
                assertEquals("main", fakeGitRunner.currentBranch)
            }

        @Test
        @DisplayName("Should extract title, description, and tags from Markdown plan file with --from-plan")
        fun testStartIssueFromPlan() =
            runBlocking {
                val planFile = tempDir.resolve("my_plan.md").toFile()
                planFile.writeText(
                    """
                    # feat(auth): OAuth2 Social Login Integration

                    ## Overview
                    Implement Google and GitHub social login handlers.

                    ## Tasks
                    - [ ] Add OAuth routes
                    - [ ] Store tokens securely
                    """.trimIndent(),
                )

                val workflowService = createWorkflowService()
                val request =
                    StartIssueRequest(
                        title = "",
                        fromPlanFile = planFile.absolutePath,
                        plan = planFile.readText(),
                    )

                val result = workflowService.startIssue(request)
                assertEquals("feat(auth): OAuth2 Social Login Integration", result.task.title)
                assertTrue(result.task.tags.contains("auth"))
                assertTrue(result.task.description.contains("OAuth routes"))
                assertTrue(result.task.logs.any { it.comment.contains("Attached implementation plan") })
            }
    }

    @Nested
    @DisplayName("Update Remote Sync Tests")
    inner class UpdateRemoteSyncTests {
        @Test
        @DisplayName("Should synchronize updated task title and description with remote issue provider")
        fun testUpdateTaskSyncsRemoteIssue() =
            runBlocking {
                val task =
                    service.createTask(
                        title = "Original Title",
                        description = "Original Description",
                        githubIssueUrl = "https://github.com/owner/repo/issues/100",
                    )
                val workflowService = createWorkflowService()

                service.updateTask(
                    taskId = task.id,
                    title = "Updated Title",
                    description = "Updated Description",
                )

                workflowService.syncTaskRemote(task.id, fakeProvider)

                assertEquals(1, fakeProvider.updatedIssues.size)
                val updated = fakeProvider.updatedIssues.first()
                assertEquals("Updated Title", updated.title)
                assertEquals("Updated Description", updated.body)
                assertEquals("https://github.com/owner/repo/issues/100", updated.issueIdOrUrl)
            }
    }

    @Nested
    @DisplayName("AiKanbanConfig & Layered Loader Tests")
    inner class ConfigWorkflowOptionsTests {
        @Test
        @DisplayName("Should load and merge workflow options config correctly")
        fun testMergeWorkflowOptionsConfig() {
            val global =
                AiKanbanConfig(
                    provider = "github",
                    workflow =
                        WorkflowOptionsConfig(
                            mergeMethod = "squash",
                            deleteBranchOnMerge = true,
                            requestColumn = "REQUEST",
                            doneColumn = "DONE",
                        ),
                )
            val project =
                AiKanbanConfig(
                    provider = "github",
                    workflow =
                        WorkflowOptionsConfig(
                            mergeMethod = "rebase",
                            deleteBranchOnMerge = false,
                        ),
                    hooks = mapOf("post-start-review" to listOf("echo review-started")),
                )

            val merged = AiKanbanConfigLoader.merge(global, project)
            assertEquals("rebase", merged.workflow.mergeMethod)
            assertFalse(merged.workflow.deleteBranchOnMerge)
            assertEquals("REQUEST", merged.workflow.requestColumn)
            assertEquals("DONE", merged.workflow.doneColumn)
            assertEquals(listOf("echo review-started"), merged.hooks["post-start-review"])
        }
    }
}
