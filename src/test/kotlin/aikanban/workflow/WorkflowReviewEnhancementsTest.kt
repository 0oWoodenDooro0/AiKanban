package aikanban.workflow

import aikanban.config.AiKanbanConfig
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
import aikanban.provider.ResourceType
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowReviewEnhancementsTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var service: KanbanService
    private lateinit var fakeGitRunner: TestGitCommandRunner
    private lateinit var fakeShellRunner: TestShellCommandRunner
    private lateinit var fakeProvider: TestIssueTrackerProvider
    private lateinit var providerFactory: ProviderFactory
    private lateinit var workflowService: DefaultKanbanWorkflowService

    class TestGitCommandRunner : GitCommandRunner {
        var currentBranch: String = "main"
        var isRepo: Boolean = true
        val checkedOutBranches = mutableListOf<String>()
        val createdBranches = mutableListOf<Pair<String, String>>()
        val pushedBranches = mutableListOf<Pair<String, String>>()
        val deletedBranches = mutableListOf<Pair<String, Boolean>>() // branch to isRemote
        val mergedBranches = mutableListOf<Pair<String, Boolean>>() // branch to isSquash
        val pulledBranches = mutableListOf<String>()

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

        override fun createBranchOnly(
            branchName: String,
            baseBranch: String,
            workingDir: File?,
        ): GitProcessResult {
            createdBranches.add(branchName to baseBranch)
            return GitProcessResult(0, "Created branch $branchName", "")
        }

        override fun checkoutBranch(
            branchName: String,
            createIfMissing: Boolean,
            baseBranch: String?,
            workingDir: File?,
        ): GitProcessResult {
            checkedOutBranches.add(branchName)
            currentBranch = branchName
            return GitProcessResult(0, "Switched to branch '$branchName'", "")
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

        override fun pull(
            remote: String,
            branch: String?,
            workingDir: File?,
        ): GitProcessResult {
            pulledBranches.add(branch ?: currentBranch)
            return GitProcessResult(0, "Already up to date.", "")
        }

        override fun pushBranch(
            branchName: String,
            remote: String,
            setUpstream: Boolean,
            workingDir: File?,
        ): GitProcessResult {
            pushedBranches.add(branchName to remote)
            return GitProcessResult(0, "Pushed $branchName", "")
        }

        override fun isGitRepository(workingDir: File?): Boolean = isRepo

        override fun getRemoteUrl(
            remote: String,
            workingDir: File?,
        ): String? = "https://github.com/0oWoodenDooro0/AiKanban.git"

        override fun addFiles(
            files: List<String>,
            workingDir: File?,
        ): GitProcessResult = GitProcessResult(0, "", "")

        override fun commit(
            message: String,
            workingDir: File?,
        ): GitProcessResult = GitProcessResult(0, "[main 1234567] $message", "")

        override fun getHeadCommitHash(workingDir: File?): String? = "1234567890abcdef"
    }

    class TestShellCommandRunner : ShellCommandRunner {
        val executedCommands = mutableListOf<String>()
        var commandHandler: (String) -> CommandExecutionResult = { cmd ->
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
            return commandHandler(command)
        }
    }

    class TestIssueTrackerProvider : IssueTrackerProvider {
        override val name: String = "test-provider"

        val approvedPrs = mutableListOf<ApprovePullRequestRequest>()
        val requestedChangesPrs = mutableListOf<RequestChangesPullRequestRequest>()
        val mergedPrs = mutableListOf<MergePullRequestRequest>()

        override fun resolveResource(url: String): ResolvedResource? {
            return ResolvedResource(
                provider = name,
                owner = "0oWoodenDooro0",
                repo = "AiKanban",
                type = ResourceType.PULL_REQUEST,
                number = 45,
                canonicalUrl = url,
            )
        }

        override suspend fun createIssue(request: CreateIssueRequest): IssueResult =
            IssueResult("1", 1, request.title, "https://github.com/0oWoodenDooro0/AiKanban/issues/1", request.body)

        override suspend fun addComment(request: AddIssueCommentRequest): Boolean = true

        override suspend fun createBranch(request: CreateBranchRequest): BranchResult =
            BranchResult(request.branchName, request.baseBranch, true)

        override suspend fun createPullRequest(request: CreatePullRequestRequest): PullRequestResult =
            PullRequestResult(
                "https://github.com/0oWoodenDooro0/AiKanban/pull/45",
                45,
                request.title,
                request.headBranch,
                request.baseBranch,
            )

        override suspend fun approvePullRequest(request: ApprovePullRequestRequest): Boolean {
            approvedPrs.add(request)
            return true
        }

        override suspend fun requestChangesPullRequest(request: RequestChangesPullRequestRequest): Boolean {
            requestedChangesPrs.add(request)
            return true
        }

        override suspend fun mergePullRequest(request: MergePullRequestRequest): Boolean {
            mergedPrs.add(request)
            return true
        }

        override suspend fun sync(request: ProviderSyncRequest): ProviderSyncResult =
            ProviderSyncResult(name, request.repoOrUrl, 0, 0, 0, 0, emptyList())
    }

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("review_enhancements_test.db").toFile()
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
        workflowService =
            DefaultKanbanWorkflowService(
                kanbanService = service,
                providerFactory = providerFactory,
                config =
                    AiKanbanConfig(
                        provider = "test-provider",
                        defaultBaseBranch = "main",
                        branchPrefix = "feature/",
                        verify = listOf("./gradlew test", "./gradlew ktlintCheck"),
                        workflow =
                            WorkflowOptionsConfig(
                                mergeMethod = "squash",
                                deleteBranchOnMerge = true,
                                requestColumn = "REQUEST",
                                doneColumn = "DONE",
                                reviewColumn = "REVIEW",
                                requireVerification = false,
                                checkoutBaseOnComplete = true,
                            ),
                    ),
                gitCommandRunner = fakeGitRunner,
                shellCommandRunner = fakeShellRunner,
                providerOverride = fakeProvider,
            )
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    @Nested
    @DisplayName("Quality Gate Verification Tests")
    inner class VerificationGateTests {
        @Test
        @DisplayName("completeReview with --verify flag fails when verification fails, blocking DONE transition")
        fun testVerifyFlagFailureBlocksTransition() =
            runBlocking {
                val task =
                    service.createTask(
                        title = "Implement complete-review quality gate",
                        status = "REVIEW",
                        operator = "reviewer",
                    )
                fakeShellRunner.commandHandler = { cmd ->
                    if (cmd.contains("gradlew test")) {
                        CommandExecutionResult(cmd, 1, "", "Unit tests failed with 2 errors", false)
                    } else {
                        CommandExecutionResult(cmd, 0, "OK", "", true)
                    }
                }

                val req =
                    CompleteReviewWorkflowRequest(
                        taskId = task.id,
                        verify = true,
                        operator = "reviewer",
                    )

                val ex =
                    assertFailsWith<IllegalStateException> {
                        workflowService.completeReview(req, tempDir.toFile())
                    }

                assertTrue(ex.message!!.contains("quality verification failure") || ex.message!!.contains("FAILED"))
                val taskAfter = service.getTask(task.id)
                assertEquals("REVIEW", taskAfter.status, "Task should remain in REVIEW when verification fails")
            }

        @Test
        @DisplayName("completeReview with config requireVerification=true blocks transition when verification fails")
        fun testConfigRequireVerificationFailureBlocksTransition() =
            runBlocking {
                val customWorkflowService =
                    DefaultKanbanWorkflowService(
                        kanbanService = service,
                        providerFactory = providerFactory,
                        config =
                            AiKanbanConfig(
                                provider = "test-provider",
                                verify = listOf("./gradlew test"),
                                workflow = WorkflowOptionsConfig(requireVerification = true),
                            ),
                        gitCommandRunner = fakeGitRunner,
                        shellCommandRunner = fakeShellRunner,
                        providerOverride = fakeProvider,
                    )

                val task = service.createTask(title = "Review task with config gate", status = "REVIEW")
                fakeShellRunner.commandHandler = { cmd ->
                    CommandExecutionResult(cmd, 1, "", "Build failure", false)
                }

                val req = CompleteReviewWorkflowRequest(taskId = task.id, verify = false)
                val ex =
                    assertFailsWith<IllegalStateException> {
                        customWorkflowService.completeReview(req, tempDir.toFile())
                    }

                assertTrue(ex.message!!.contains("verification"))
                assertEquals("REVIEW", service.getTask(task.id).status)
            }

        @Test
        @DisplayName("completeReview with --verify succeeds when verification commands pass, moving task to DONE")
        fun testVerifyFlagSuccessTransitionsToDone() =
            runBlocking {
                val task = service.createTask(title = "Task with passing verification", status = "REVIEW")
                val req = CompleteReviewWorkflowRequest(taskId = task.id, verify = true)

                val result = workflowService.completeReview(req, tempDir.toFile())

                assertEquals("DONE", result.task.status)
                assertEquals(true, result.verificationPassed)
                assertTrue(fakeShellRunner.executedCommands.contains("./gradlew test"))
                assertTrue(fakeShellRunner.executedCommands.contains("./gradlew ktlintCheck"))
            }

        @Test
        @DisplayName("completeReview skips verification when verify=false and requireVerification=false")
        fun testVerificationSkippedWhenNotRequested() =
            runBlocking {
                val task = service.createTask(title = "Task without verification", status = "REVIEW")
                val req = CompleteReviewWorkflowRequest(taskId = task.id, verify = false)

                val result = workflowService.completeReview(req, tempDir.toFile())

                assertEquals("DONE", result.task.status)
                assertNull(result.verificationPassed)
                assertEquals(0, fakeShellRunner.executedCommands.size)
            }
    }

    @Nested
    @DisplayName("Base Branch Checkout Tests")
    inner class BaseBranchCheckoutTests {
        @Test
        @DisplayName("completeReview with checkoutBase=true (default) switches workspace branch to main")
        fun testDefaultCheckoutBaseSwitchesToMain() =
            runBlocking {
                fakeGitRunner.currentBranch = "feature/complete-review-enhancements"
                val task = service.createTask(title = "Checkout Base Test", status = "REVIEW")

                val result =
                    workflowService.completeReview(
                        CompleteReviewWorkflowRequest(taskId = task.id, checkoutBase = true),
                        tempDir.toFile(),
                    )

                assertEquals("DONE", result.task.status)
                assertEquals("main", result.baseBranch)
                assertEquals("main", fakeGitRunner.currentBranch)
                assertTrue(fakeGitRunner.checkedOutBranches.contains("main"))
            }

        @Test
        @DisplayName("completeReview with checkoutBase=false preserves current branch without switching")
        fun testNoCheckoutBasePreservesCurrentBranch() =
            runBlocking {
                fakeGitRunner.currentBranch = "feature/preserve-this-branch"
                val task = service.createTask(title = "No Checkout Base Test", status = "REVIEW")

                val result =
                    workflowService.completeReview(
                        CompleteReviewWorkflowRequest(taskId = task.id, checkoutBase = false),
                        tempDir.toFile(),
                    )

                assertEquals("DONE", result.task.status)
                assertNull(result.baseBranch)
                assertEquals("feature/preserve-this-branch", fakeGitRunner.currentBranch)
                assertFalse(fakeGitRunner.checkedOutBranches.contains("main"))
            }

        @Test
        @DisplayName("completeReview with custom targetBaseBranch checks out specified base branch")
        fun testCustomTargetBaseBranchCheckout() =
            runBlocking {
                fakeGitRunner.currentBranch = "feature/custom-base"
                val task = service.createTask(title = "Custom Base Test", status = "REVIEW")

                val result =
                    workflowService.completeReview(
                        CompleteReviewWorkflowRequest(taskId = task.id, checkoutBase = true, targetBaseBranch = "develop"),
                        tempDir.toFile(),
                    )

                assertEquals("develop", result.baseBranch)
                assertEquals("develop", fakeGitRunner.currentBranch)
                assertTrue(fakeGitRunner.checkedOutBranches.contains("develop"))
            }

        @Test
        @DisplayName("completeReview with pullBase=true executes git pull on target base branch")
        fun testPullBaseExecutesGitPull() =
            runBlocking {
                fakeGitRunner.currentBranch = "feature/pull-base"
                val task = service.createTask(title = "Pull Base Test", status = "REVIEW")

                val result =
                    workflowService.completeReview(
                        CompleteReviewWorkflowRequest(taskId = task.id, checkoutBase = true, pullBase = true),
                        tempDir.toFile(),
                    )

                assertEquals("main", result.baseBranch)
                assertTrue(fakeGitRunner.pulledBranches.contains("main"))
            }
    }

    @Nested
    @DisplayName("Branch Cleanup Tests")
    inner class BranchCleanupTests {
        @Test
        @DisplayName("completeReview with deleteBranch=true deletes local feature branch")
        fun testDeleteBranchFlagDeletesFeatureBranchLocally() =
            runBlocking {
                val task = service.createTask(title = "Delete Branch Flag Test", status = "REVIEW")
                service.addComment(task.id, "workflow", "Created branch feature/delete-branch-flag-test")
                fakeGitRunner.currentBranch = "feature/delete-branch-flag-test"

                val result =
                    workflowService.completeReview(
                        CompleteReviewWorkflowRequest(taskId = task.id, deleteBranch = true),
                        tempDir.toFile(),
                    )

                assertEquals("DONE", result.task.status)
                assertEquals("feature/delete-branch-flag-test", result.deletedBranch)
                assertTrue(fakeGitRunner.deletedBranches.any { it.first == "feature/delete-branch-flag-test" && !it.second })
            }

        @Test
        @DisplayName("completeReview with merge=true and deleteBranchOnMerge=true deletes feature branch")
        fun testMergeWithDeleteBranchOnMergeConfigDeletesFeatureBranch() =
            runBlocking {
                val task =
                    service.createTask(
                        title = "Merge and Delete Branch Test",
                        status = "REVIEW",
                    )
                service.updateTask(task.id, githubPrUrl = "https://github.com/0oWoodenDooro0/AiKanban/pull/45")
                service.addComment(task.id, "workflow", "Created branch feature/merge-and-delete")
                fakeGitRunner.currentBranch = "feature/merge-and-delete"

                val result =
                    workflowService.completeReview(
                        CompleteReviewWorkflowRequest(taskId = task.id, merge = true, deleteBranch = null),
                        tempDir.toFile(),
                    )

                assertEquals("DONE", result.task.status)
                assertTrue(result.merged)
                assertEquals("feature/merge-and-delete", result.deletedBranch)
                assertTrue(fakeGitRunner.deletedBranches.any { it.first == "feature/merge-and-delete" })
            }

        @Test
        @DisplayName("completeReview with deleteBranch=false overrides deleteBranchOnMerge config and skips deletion")
        fun testNoDeleteBranchFlagOverridesConfig() =
            runBlocking {
                val task = service.createTask(title = "Keep Branch Test", status = "REVIEW")
                service.addComment(task.id, "workflow", "Created branch feature/keep-branch")

                val result =
                    workflowService.completeReview(
                        CompleteReviewWorkflowRequest(taskId = task.id, merge = true, deleteBranch = false),
                        tempDir.toFile(),
                    )

                assertEquals("DONE", result.task.status)
                assertNull(result.deletedBranch)
                assertTrue(fakeGitRunner.deletedBranches.none { it.first == "feature/keep-branch" })
            }

        @Test
        @DisplayName("completeReview does not delete branch if resolved branch equals target base branch")
        fun testDoesNotDeleteBaseBranch() =
            runBlocking {
                val task = service.createTask(title = "Base Branch Safety Test", status = "REVIEW")
                fakeGitRunner.currentBranch = "main"

                val result =
                    workflowService.completeReview(
                        CompleteReviewWorkflowRequest(taskId = task.id, deleteBranch = true, targetBaseBranch = "main"),
                        tempDir.toFile(),
                    )

                assertEquals("DONE", result.task.status)
                assertNull(result.deletedBranch)
                assertTrue(fakeGitRunner.deletedBranches.none { it.first == "main" })
            }
    }

    @Nested
    @DisplayName("Lifecycle Hooks Tests")
    inner class LifecycleHooksTests {
        @Test
        @DisplayName("completeReview executes pre-complete-review and post-complete-review hooks in order")
        fun testExecutesPreAndPostHooks() =
            runBlocking {
                val hookWorkflowService =
                    DefaultKanbanWorkflowService(
                        kanbanService = service,
                        providerFactory = providerFactory,
                        config =
                            AiKanbanConfig(
                                provider = "test-provider",
                                hooks =
                                    mapOf(
                                        "pre-complete-review" to listOf("echo pre-check"),
                                        "post-complete-review" to listOf("echo post-cleanup"),
                                    ),
                            ),
                        gitCommandRunner = fakeGitRunner,
                        shellCommandRunner = fakeShellRunner,
                        providerOverride = fakeProvider,
                    )

                val task = service.createTask(title = "Hook Lifecycle Test", status = "REVIEW")

                val result = hookWorkflowService.completeReview(CompleteReviewWorkflowRequest(taskId = task.id), tempDir.toFile())

                assertEquals("DONE", result.task.status)
                assertEquals(2, result.executedHooks.size)
                assertEquals(listOf("echo pre-check", "echo post-cleanup"), fakeShellRunner.executedCommands)
            }

        @Test
        @DisplayName("completeReview aborts when pre-complete-review hook fails, task remains in REVIEW")
        fun testPreHookFailureAbortsCompletion() =
            runBlocking {
                val hookWorkflowService =
                    DefaultKanbanWorkflowService(
                        kanbanService = service,
                        providerFactory = providerFactory,
                        config =
                            AiKanbanConfig(
                                provider = "test-provider",
                                hooks = mapOf("pre-complete-review" to listOf("./failing-hook.sh")),
                            ),
                        gitCommandRunner = fakeGitRunner,
                        shellCommandRunner = fakeShellRunner,
                        providerOverride = fakeProvider,
                    )

                val task = service.createTask(title = "Hook Failure Test", status = "REVIEW")
                fakeShellRunner.commandHandler = { cmd ->
                    CommandExecutionResult(cmd, 2, "", "Pre-complete hook failed", false)
                }

                val ex =
                    assertFailsWith<IllegalStateException> {
                        hookWorkflowService.completeReview(CompleteReviewWorkflowRequest(taskId = task.id), tempDir.toFile())
                    }

                assertTrue(ex.message!!.contains("Pre-complete-review hook failed"))
                assertEquals("REVIEW", service.getTask(task.id).status)
            }
    }

    @Nested
    @DisplayName("Combined Workflow Execution Tests")
    inner class CombinedWorkflowTests {
        @Test
        @DisplayName("completeReview with full combination (--verify, --merge, --checkout-base, --delete-branch, --pull-base) succeeds")
        fun testCompleteReviewFullLifecycle() =
            runBlocking {
                val task =
                    service.createTask(
                        title = "Full Lifecycle Feature",
                        status = "REVIEW",
                    )
                service.updateTask(task.id, githubPrUrl = "https://github.com/0oWoodenDooro0/AiKanban/pull/45")
                service.addComment(task.id, "workflow", "Created branch feature/full-lifecycle-feature")
                fakeGitRunner.currentBranch = "feature/full-lifecycle-feature"

                val req =
                    CompleteReviewWorkflowRequest(
                        taskId = task.id,
                        merge = true,
                        verify = true,
                        checkoutBase = true,
                        deleteBranch = true,
                        pullBase = true,
                        comment = "LGTM: approved and merged with all quality checks",
                        operator = "lead-reviewer",
                    )

                val result = workflowService.completeReview(req, tempDir.toFile())

                // Status & log
                assertEquals("DONE", result.task.status)
                assertTrue(result.merged)
                assertEquals("main", result.baseBranch)
                assertEquals("feature/full-lifecycle-feature", result.deletedBranch)
                assertEquals(true, result.verificationPassed)

                // Audit logs on task
                val taskInDb = service.getTask(task.id)
                assertEquals("DONE", taskInDb.status)
                val lastLog = taskInDb.logs.last()
                assertEquals("DONE", lastLog.toStatus)
                assertEquals("lead-reviewer", lastLog.operator)
                assertEquals("LGTM: approved and merged with all quality checks", lastLog.comment)

                // Remote PR merged
                assertEquals(1, fakeProvider.mergedPrs.size)
                assertEquals("https://github.com/0oWoodenDooro0/AiKanban/pull/45", fakeProvider.mergedPrs.first().prNumberOrUrl)

                // Git runner verified
                assertTrue(fakeGitRunner.checkedOutBranches.contains("main"))
                assertTrue(fakeGitRunner.pulledBranches.contains("main"))
                assertTrue(fakeGitRunner.deletedBranches.any { it.first == "feature/full-lifecycle-feature" })
            }
    }

    @Nested
    @DisplayName("Remote PR Review Verdict Sync Tests")
    inner class RemotePrReviewSyncTests {
        @Test
        @DisplayName("completeReview with githubPrUrl invokes provider.approvePullRequest with prUrl and comment")
        fun testCompleteReviewApprovesRemotePullRequest() =
            runBlocking {
                val task =
                    service.createTask(
                        title = "PR Review Sync Task",
                        status = "REVIEW",
                    )
                service.updateTask(task.id, githubPrUrl = "https://github.com/0oWoodenDooro0/AiKanban/pull/45")

                val req =
                    CompleteReviewWorkflowRequest(
                        taskId = task.id,
                        comment = "LGTM: approved!",
                        merge = false,
                    )

                val result = workflowService.completeReview(req, tempDir.toFile())

                assertEquals("DONE", result.task.status)
                assertEquals(1, fakeProvider.approvedPrs.size)
                val approvedPr = fakeProvider.approvedPrs.first()
                assertEquals("https://github.com/0oWoodenDooro0/AiKanban/pull/45", approvedPr.prNumberOrUrl)
                assertEquals("LGTM: approved!", approvedPr.comment)
                assertEquals(0, fakeProvider.mergedPrs.size)
            }

        @Test
        @DisplayName("completeReview with merge=true also approves PR before/during merging")
        fun testCompleteReviewWithMergeAlsoApprovesPr() =
            runBlocking {
                val task =
                    service.createTask(
                        title = "PR Review Merge Sync Task",
                        status = "REVIEW",
                    )
                service.updateTask(task.id, githubPrUrl = "https://github.com/0oWoodenDooro0/AiKanban/pull/45")

                val req =
                    CompleteReviewWorkflowRequest(
                        taskId = task.id,
                        comment = "LGTM and merged",
                        merge = true,
                    )

                val result = workflowService.completeReview(req, tempDir.toFile())

                assertEquals("DONE", result.task.status)
                assertTrue(result.merged)
                assertEquals(1, fakeProvider.approvedPrs.size)
                assertEquals("https://github.com/0oWoodenDooro0/AiKanban/pull/45", fakeProvider.approvedPrs.first().prNumberOrUrl)
                assertEquals("LGTM and merged", fakeProvider.approvedPrs.first().comment)
                assertEquals(1, fakeProvider.mergedPrs.size)
                assertEquals("https://github.com/0oWoodenDooro0/AiKanban/pull/45", fakeProvider.mergedPrs.first().prNumberOrUrl)
            }

        @Test
        @DisplayName("completeReview without githubPrUrl skips provider.approvePullRequest")
        fun testCompleteReviewSkipsRemoteReviewWhenNoPrUrl() =
            runBlocking {
                val task = service.createTask(title = "Local review task", status = "REVIEW")

                val req =
                    CompleteReviewWorkflowRequest(
                        taskId = task.id,
                        comment = "Local approval",
                        merge = false,
                    )

                val result = workflowService.completeReview(req, tempDir.toFile())

                assertEquals("DONE", result.task.status)
                assertEquals(0, fakeProvider.approvedPrs.size)
            }

        @Test
        @DisplayName("requestChanges with githubPrUrl invokes provider.requestChangesPullRequest with prUrl and comment")
        fun testRequestChangesSubmitsRemoteRequestChangesReview() =
            runBlocking {
                val task =
                    service.createTask(
                        title = "Rework Required Task",
                        status = "REVIEW",
                    )
                service.updateTask(task.id, githubPrUrl = "https://github.com/0oWoodenDooro0/AiKanban/pull/45")

                val req =
                    RequestChangesWorkflowRequest(
                        taskId = task.id,
                        comment = "Please fix broken unit tests and format code",
                        operator = "lead-reviewer",
                    )

                val result = workflowService.requestChanges(req)

                assertEquals("REQUEST", result.task.status)
                assertEquals(1, fakeProvider.requestedChangesPrs.size)
                val reqChanges = fakeProvider.requestedChangesPrs.first()
                assertEquals("https://github.com/0oWoodenDooro0/AiKanban/pull/45", reqChanges.prNumberOrUrl)
                assertEquals("Please fix broken unit tests and format code", reqChanges.comment)
            }

        @Test
        @DisplayName("requestChanges without githubPrUrl skips provider.requestChangesPullRequest")
        fun testRequestChangesSkipsRemoteReviewWhenNoPrUrl() =
            runBlocking {
                val task = service.createTask(title = "Local rework task", status = "REVIEW")

                val req =
                    RequestChangesWorkflowRequest(
                        taskId = task.id,
                        comment = "Local change request",
                    )

                val result = workflowService.requestChanges(req)

                assertEquals("REQUEST", result.task.status)
                assertEquals(0, fakeProvider.requestedChangesPrs.size)
            }
    }
}
