package aikanban.workflow

import aikanban.config.AiKanbanConfig
import aikanban.model.Task
import aikanban.model.TaskPriority
import aikanban.provider.AddIssueCommentRequest
import aikanban.provider.BranchResult
import aikanban.provider.CreateBranchRequest
import aikanban.provider.CreateIssueRequest
import aikanban.provider.CreatePullRequestRequest
import aikanban.provider.DefaultGitCommandRunner
import aikanban.provider.GitCommandRunner
import aikanban.provider.IssueResult
import aikanban.provider.IssueTrackerProvider
import aikanban.provider.MergePullRequestRequest
import aikanban.provider.ProviderFactory
import aikanban.provider.PullRequestResult
import aikanban.provider.RequestChangesPullRequestRequest
import aikanban.provider.UpdateIssueRequest
import aikanban.service.KanbanService
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class StartIssueRequest(
    val title: String,
    val description: String = "",
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val tags: Set<String> = emptySet(),
    val branchName: String? = null,
    val baseBranch: String = "main",
    val plan: String? = null,
    val assignee: String? = null,
    val operator: String = "workflow",
    val providerName: String? = null,
    val dryRun: Boolean = false,
)

@Serializable
data class StartIssueResult(
    val task: Task,
    val issue: IssueResult,
    val branch: BranchResult,
)

@Serializable
data class SubmitPrRequest(
    val taskId: Int,
    val title: String? = null,
    val body: String? = null,
    val headBranch: String? = null,
    val baseBranch: String = "main",
    val draft: Boolean = false,
    val operator: String = "workflow",
    val providerName: String? = null,
    val dryRun: Boolean = false,
)

@Serializable
data class SubmitPrResult(
    val task: Task,
    val pr: PullRequestResult,
)

@Serializable
data class StartReviewRequest(
    val taskId: Int? = null,
    val operator: String = "workflow",
    val checkoutBranch: Boolean = true,
)

@Serializable
data class StartReviewResult(
    val task: Task,
    val branchName: String?,
    val prUrl: String?,
    val executedHooks: List<CommandExecutionResult> = emptyList(),
)

@Serializable
data class RequestChangesWorkflowRequest(
    val taskId: Int,
    val comment: String,
    val operator: String = "workflow",
    val providerName: String? = null,
)

typealias RequestChangesRequest = RequestChangesWorkflowRequest

@Serializable
data class RequestChangesWorkflowResult(
    val task: Task,
    val comment: String,
    val prUrl: String?,
)

typealias RequestChangesResult = RequestChangesWorkflowResult

@Serializable
data class CompleteReviewWorkflowRequest(
    val taskId: Int,
    val merge: Boolean = false,
    val comment: String? = null,
    val operator: String = "workflow",
    val providerName: String? = null,
    val verify: Boolean = false,
    val checkoutBase: Boolean = true,
    val deleteBranch: Boolean? = null,
    val pullBase: Boolean = false,
    val targetBaseBranch: String? = null,
)

typealias CompleteReviewRequest = CompleteReviewWorkflowRequest

@Serializable
data class CompleteReviewWorkflowResult(
    val task: Task,
    val merged: Boolean,
    val prUrl: String?,
    val baseBranch: String? = null,
    val deletedBranch: String? = null,
    val verificationPassed: Boolean? = null,
    val executedHooks: List<CommandExecutionResult> = emptyList(),
)

typealias CompleteReviewResult = CompleteReviewWorkflowResult

@Serializable
data class CommandExecutionResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean,
)

@Serializable
data class VerifyResult(
    val success: Boolean,
    val executedCommands: List<CommandExecutionResult>,
    val message: String,
)

@Serializable
data class StepExecutionResult(
    val step: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean,
)

@Serializable
data class CustomWorkflowResult(
    val workflowName: String,
    val success: Boolean,
    val executedSteps: List<StepExecutionResult>,
    val message: String,
)

@Serializable
data class StartTaskRequest(
    val taskId: Int,
    val assignee: String? = null,
    val operator: String = "workflow",
)

@Serializable
data class CommitTaskRequest(
    val taskId: Int,
    val message: String,
    val files: List<String> = emptyList(),
    val operator: String = "workflow",
    val executeGitCommit: Boolean = true,
)

@Serializable
data class CommitTaskResult(
    val task: Task,
    val commitHash: String?,
    val message: String,
    val executedHooks: List<CommandExecutionResult> = emptyList(),
)

interface ShellCommandRunner {
    fun execute(
        command: String,
        workingDir: File = File("."),
    ): CommandExecutionResult
}

class DefaultShellCommandRunner : ShellCommandRunner {
    override fun execute(
        command: String,
        workingDir: File,
    ): CommandExecutionResult {
        val osName = System.getProperty("os.name") ?: ""
        val isWindows = osName.contains("win", ignoreCase = true)
        val processBuilder =
            if (isWindows) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }
        processBuilder.directory(workingDir.absoluteFile)
        processBuilder.redirectErrorStream(false)
        return try {
            val process = processBuilder.start()
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                CommandExecutionResult(
                    command = command,
                    exitCode = -1,
                    stdout = stdout,
                    stderr = "Command timed out after 60 seconds",
                    success = false,
                )
            } else {
                CommandExecutionResult(
                    command = command,
                    exitCode = process.exitValue(),
                    stdout = stdout,
                    stderr = stderr,
                    success = process.exitValue() == 0,
                )
            }
        } catch (e: Exception) {
            CommandExecutionResult(
                command = command,
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Failed to execute command",
                success = false,
            )
        }
    }
}

interface KanbanWorkflowService {
    suspend fun startIssue(request: StartIssueRequest): StartIssueResult

    suspend fun submitPr(request: SubmitPrRequest): SubmitPrResult

    suspend fun startReview(
        request: StartReviewRequest,
        workingDir: File? = null,
    ): StartReviewResult

    suspend fun requestChanges(request: RequestChangesWorkflowRequest): RequestChangesWorkflowResult

    suspend fun completeReview(
        request: CompleteReviewWorkflowRequest,
        workingDir: File? = null,
    ): CompleteReviewWorkflowResult

    suspend fun syncTaskRemote(
        taskId: Int,
        providerOverride: IssueTrackerProvider? = null,
    ): Task

    suspend fun runVerify(
        commands: List<String>? = null,
        workingDir: File? = null,
    ): VerifyResult

    suspend fun runWorkflow(
        workflowName: String,
        workingDir: File? = null,
    ): CustomWorkflowResult

    suspend fun startTask(request: StartTaskRequest): Task

    suspend fun commitTask(
        request: CommitTaskRequest,
        workingDir: File? = null,
    ): CommitTaskResult
}

class DefaultKanbanWorkflowService(
    private val kanbanService: KanbanService,
    private val providerFactory: ProviderFactory,
    private val config: AiKanbanConfig = AiKanbanConfig(),
    private val gitCommandRunner: GitCommandRunner = DefaultGitCommandRunner(),
    private val shellCommandRunner: ShellCommandRunner = DefaultShellCommandRunner(),
    private val providerOverride: IssueTrackerProvider? = null,
) : KanbanWorkflowService {
    companion object {
        fun slugify(text: String): String {
            return text
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(50)
                .trim('-')
        }
    }

    private fun resolveTaskBranch(
        task: Task,
        workingDir: File?,
        fallbackToGenerated: Boolean = false,
    ): String? {
        val branchFromLog =
            task.logs.reversed().firstNotNullOfOrNull { log ->
                val text = log.comment ?: ""
                Regex("""(?:Created|Switched to|Created and switched to)\s+branch\s+([^\s]+)""").find(text)?.groupValues?.get(1)
            }
        if (branchFromLog != null) return branchFromLog

        if (task.githubPrUrl != null && task.githubPrUrl.startsWith("local://pull/")) {
            return task.githubPrUrl.removePrefix("local://pull/")
        }

        val cur = gitCommandRunner.getCurrentBranch(workingDir)
        if (cur != "HEAD" && cur.isNotBlank() && cur != config.defaultBaseBranch && cur != "main") {
            return cur
        }

        return if (fallbackToGenerated) "${config.branchPrefix}${slugify(task.title)}" else null
    }

    override suspend fun startIssue(request: StartIssueRequest): StartIssueResult {
        val provider = providerOverride ?: providerFactory.resolve(request.providerName, config)
        val branchName =
            request.branchName?.trim()?.takeIf { it.isNotBlank() }
                ?: "${config.branchPrefix}${slugify(request.title)}"

        val issueResult =
            provider.createIssue(
                CreateIssueRequest(
                    title = request.title,
                    body = request.description,
                    labels = request.tags,
                    priority = request.priority,
                    assignee = request.assignee,
                ),
            )

        if (request.dryRun) {
            val previewTask =
                Task(
                    id = 0,
                    title = request.title,
                    description = request.description,
                    priority = request.priority,
                    assignee = request.assignee,
                    tags = request.tags,
                    githubIssueUrl = issueResult.url,
                    status = "TODO",
                )
            val previewBranch =
                BranchResult(
                    branchName = branchName,
                    baseBranch = request.baseBranch,
                    created = true,
                    linkedIssueUrl = issueResult.url,
                    message = "Dry-run branch preview",
                )
            return StartIssueResult(previewTask, issueResult, previewBranch)
        }

        val task =
            kanbanService.createTask(
                title = request.title,
                description = request.description,
                priority = request.priority,
                assignee = request.assignee,
                tags = request.tags,
                githubIssueUrl = issueResult.url,
                status = "TODO",
                operator = request.operator,
            )

        if (!request.plan.isNullOrBlank()) {
            provider.addComment(
                AddIssueCommentRequest(
                    issueIdOrUrl = issueResult.url ?: issueResult.id,
                    comment = request.plan,
                ),
            )
            kanbanService.addComment(
                taskId = task.id,
                operator = request.operator,
                comment = "Attached implementation plan",
            )
        }

        val branchResult =
            provider.createBranch(
                CreateBranchRequest(
                    branchName = branchName,
                    baseBranch = request.baseBranch,
                    issueIdOrUrl = issueResult.url,
                ),
            )

        kanbanService.addComment(
            taskId = task.id,
            operator = request.operator,
            comment = "Created branch $branchName",
        )

        return StartIssueResult(
            task = kanbanService.getTask(task.id),
            issue = issueResult,
            branch = branchResult,
        )
    }

    override suspend fun submitPr(request: SubmitPrRequest): SubmitPrResult {
        val provider = providerOverride ?: providerFactory.resolve(request.providerName, config)
        val task = kanbanService.getTask(request.taskId)

        val headBranch =
            request.headBranch?.trim()?.takeIf { it.isNotBlank() }
                ?: gitCommandRunner.getCurrentBranch()
        val prTitle = request.title?.trim()?.takeIf { it.isNotBlank() } ?: task.title
        val prBody = request.body ?: task.description

        if (request.dryRun) {
            val previewPr =
                PullRequestResult(
                    url = "https://preview/pr/${task.id}",
                    number = task.id,
                    title = prTitle,
                    headBranch = headBranch,
                    baseBranch = request.baseBranch,
                    draft = request.draft,
                )
            return SubmitPrResult(task, previewPr)
        }

        val preHooks = config.hooks["pre-submit-pr"] ?: emptyList()
        for (hook in preHooks) {
            val hookRes = shellCommandRunner.execute(hook)
            if (!hookRes.success) {
                throw IllegalStateException(
                    "Pre-submit-pr hook failed: $hook (exit code ${hookRes.exitCode}): ${hookRes.stderr.ifBlank { hookRes.stdout }}",
                )
            }
        }

        val prResult =
            provider.createPullRequest(
                CreatePullRequestRequest(
                    title = prTitle,
                    body = prBody,
                    headBranch = headBranch,
                    baseBranch = request.baseBranch,
                    draft = request.draft,
                ),
            )

        val updatedTask =
            kanbanService.moveTask(
                taskId = task.id,
                toStatus = "REVIEW",
                operator = request.operator,
                comment = "Submitted pull request: ${prResult.url}",
                prUrl = prResult.url,
            )

        val postHooks = config.hooks["post-submit-pr"] ?: emptyList()
        for (hook in postHooks) {
            shellCommandRunner.execute(hook)
        }

        return SubmitPrResult(
            task = updatedTask,
            pr = prResult,
        )
    }

    override suspend fun startReview(
        request: StartReviewRequest,
        workingDir: File?,
    ): StartReviewResult {
        val dir = workingDir ?: File(".")
        val targetTask =
            if (request.taskId != null) {
                kanbanService.getTask(request.taskId)
            } else {
                val reviewColumn = config.workflow.reviewColumn.ifBlank { "REVIEW" }
                val tasks = kanbanService.listTasks(status = reviewColumn)
                tasks.firstOrNull() ?: throw IllegalArgumentException("No tasks currently in $reviewColumn status for review.")
            }

        val preHooks = config.hooks["pre-start-review"] ?: emptyList()
        val executedHooks = mutableListOf<CommandExecutionResult>()
        for (hook in preHooks) {
            val res = shellCommandRunner.execute(hook, dir)
            executedHooks.add(res)
            if (!res.success) {
                throw IllegalStateException(
                    "Pre-start-review hook failed: $hook (exit code ${res.exitCode}): ${res.stderr.ifBlank { res.stdout }}",
                )
            }
        }

        val branchName = resolveTaskBranch(targetTask, dir, fallbackToGenerated = true)
        if (request.checkoutBranch && branchName != null && gitCommandRunner.isGitRepository(dir)) {
            gitCommandRunner.checkoutBranch(branchName, createIfMissing = false, workingDir = dir)
        }

        kanbanService.addComment(
            taskId = targetTask.id,
            operator = request.operator,
            comment = "Started code review${branchName?.let { " on branch $it" } ?: ""}",
        )

        val postHooks = config.hooks["post-start-review"] ?: emptyList()
        for (hook in postHooks) {
            val res = shellCommandRunner.execute(hook, dir)
            executedHooks.add(res)
        }

        val updatedTask = kanbanService.getTask(targetTask.id)
        return StartReviewResult(
            task = updatedTask,
            branchName = branchName,
            prUrl = targetTask.githubPrUrl,
            executedHooks = executedHooks,
        )
    }

    override suspend fun requestChanges(request: RequestChangesWorkflowRequest): RequestChangesWorkflowResult {
        val provider = providerOverride ?: providerFactory.resolve(request.providerName, config)
        val task = kanbanService.getTask(request.taskId)
        val requestColumn = config.workflow.requestColumn.ifBlank { "REQUEST" }

        val updatedTask =
            kanbanService.moveTask(
                taskId = task.id,
                toStatus = requestColumn,
                operator = request.operator,
                comment = "Changes requested: ${request.comment}",
            )

        val prUrl = task.githubPrUrl
        if (!prUrl.isNullOrBlank()) {
            provider.requestChangesPullRequest(
                RequestChangesPullRequestRequest(
                    prNumberOrUrl = prUrl,
                    comment = request.comment,
                ),
            )
        }

        return RequestChangesWorkflowResult(
            task = updatedTask,
            comment = request.comment,
            prUrl = prUrl,
        )
    }

    override suspend fun completeReview(
        request: CompleteReviewWorkflowRequest,
        workingDir: File?,
    ): CompleteReviewWorkflowResult {
        val dir = workingDir ?: File(".")
        val provider = providerOverride ?: providerFactory.resolve(request.providerName, config)
        val task = kanbanService.getTask(request.taskId)
        val doneColumn = config.workflow.doneColumn.ifBlank { "DONE" }

        val shouldVerify = request.verify || config.workflow.requireVerification
        var verificationPassed: Boolean? = null
        if (shouldVerify) {
            val verifyRes = runVerify(workingDir = dir)
            if (!verifyRes.success) {
                throw IllegalStateException(
                    "Review completion blocked by quality verification failure: ${verifyRes.message}",
                )
            }
            verificationPassed = true
        }

        val executedHooks = mutableListOf<CommandExecutionResult>()
        val preHooks = config.hooks["pre-complete-review"] ?: emptyList()
        for (hook in preHooks) {
            val res = shellCommandRunner.execute(hook, dir)
            executedHooks.add(res)
            if (!res.success) {
                throw IllegalStateException(
                    "Pre-complete-review hook failed: $hook (exit code ${res.exitCode}): ${res.stderr.ifBlank { res.stdout }}",
                )
            }
        }

        val resolvedBranch = resolveTaskBranch(task, dir)
        val targetBase = request.targetBaseBranch ?: config.defaultBaseBranch.ifBlank { "main" }
        val prUrl = task.githubPrUrl
        val shouldDelete = request.deleteBranch ?: (request.merge && config.workflow.deleteBranchOnMerge)

        if (request.merge) {
            if (!prUrl.isNullOrBlank()) {
                provider.mergePullRequest(
                    MergePullRequestRequest(
                        prNumberOrUrl = prUrl,
                        mergeMethod = config.workflow.mergeMethod,
                        deleteBranch = shouldDelete,
                    ),
                )
            } else if (resolvedBranch != null && gitCommandRunner.isGitRepository(dir)) {
                gitCommandRunner.mergeBranch(
                    resolvedBranch,
                    squash = config.workflow.mergeMethod.equals("squash", ignoreCase = true),
                    workingDir = dir,
                )
            }
        }

        var baseBranchResult: String? = null
        if (request.checkoutBase && gitCommandRunner.isGitRepository(dir)) {
            gitCommandRunner.checkoutBranch(targetBase, createIfMissing = false, workingDir = dir)
            if (request.pullBase) {
                gitCommandRunner.pull("origin", targetBase, dir)
            }
            baseBranchResult = targetBase
        }

        var deletedBranchResult: String? = null
        if (shouldDelete && resolvedBranch != null && resolvedBranch != targetBase) {
            if (gitCommandRunner.isGitRepository(dir)) {
                if (gitCommandRunner.getCurrentBranch(dir) == resolvedBranch) {
                    gitCommandRunner.checkoutBranch(targetBase, createIfMissing = false, workingDir = dir)
                }
                val delRes = gitCommandRunner.deleteBranch(resolvedBranch, force = true, remote = false, workingDir = dir)
                if (delRes.exitCode == 0) {
                    deletedBranchResult = resolvedBranch
                }
            }
        }

        val logComment = request.comment ?: if (request.merge) "Review approved and merged" else "Review approved"
        val updatedTask =
            kanbanService.moveTask(
                taskId = task.id,
                toStatus = doneColumn,
                operator = request.operator,
                comment = logComment,
            )

        val postHooks = config.hooks["post-complete-review"] ?: emptyList()
        for (hook in postHooks) {
            val res = shellCommandRunner.execute(hook, dir)
            executedHooks.add(res)
        }

        return CompleteReviewWorkflowResult(
            task = updatedTask,
            merged = request.merge,
            prUrl = prUrl,
            baseBranch = baseBranchResult,
            deletedBranch = deletedBranchResult,
            verificationPassed = verificationPassed,
            executedHooks = executedHooks,
        )
    }

    override suspend fun syncTaskRemote(
        taskId: Int,
        providerOverride: IssueTrackerProvider?,
    ): Task {
        val task = kanbanService.getTask(taskId)
        val target = task.githubIssueUrl ?: task.githubRepo
        if (!target.isNullOrBlank()) {
            val provider = providerOverride ?: this.providerOverride ?: providerFactory.resolve(null, config)
            provider.updateIssue(
                UpdateIssueRequest(
                    issueIdOrUrl = target,
                    title = task.title,
                    body = task.description,
                ),
            )
        }
        return task
    }

    override suspend fun runVerify(
        commands: List<String>?,
        workingDir: File?,
    ): VerifyResult {
        val dir = workingDir ?: File(".")
        val cmds = commands ?: config.verify
        if (cmds.isEmpty()) {
            return VerifyResult(
                success = true,
                executedCommands = emptyList(),
                message = "No verification commands configured",
            )
        }

        val executed = mutableListOf<CommandExecutionResult>()
        var allPassed = true
        for (cmd in cmds) {
            val res = shellCommandRunner.execute(cmd, dir)
            executed.add(res)
            if (!res.success) {
                allPassed = false
                break
            }
        }

        val message =
            if (allPassed) {
                "All verification checks passed (${executed.size}/${cmds.size})"
            } else {
                "Verification FAILED at: ${executed.lastOrNull()?.command}"
            }

        return VerifyResult(
            success = allPassed,
            executedCommands = executed,
            message = message,
        )
    }

    override suspend fun runWorkflow(
        workflowName: String,
        workingDir: File?,
    ): CustomWorkflowResult {
        val dir = workingDir ?: File(".")
        val wf =
            config.workflows[workflowName]
                ?: throw IllegalArgumentException(
                    "Workflow '$workflowName' not defined in configuration. " +
                        "Available workflows: ${config.workflows.keys.joinToString(", ")}",
                )

        val executed = mutableListOf<StepExecutionResult>()
        var success = true
        for (step in wf.steps) {
            val res = shellCommandRunner.execute(step, dir)
            executed.add(
                StepExecutionResult(
                    step = step,
                    exitCode = res.exitCode,
                    stdout = res.stdout,
                    stderr = res.stderr,
                    success = res.success,
                ),
            )
            if (!res.success) {
                success = false
                break
            }
        }

        val message =
            if (success) {
                "Workflow '$workflowName' completed successfully"
            } else {
                "Workflow '$workflowName' failed at step: ${executed.lastOrNull()?.step}"
            }

        return CustomWorkflowResult(
            workflowName = workflowName,
            success = success,
            executedSteps = executed,
            message = message,
        )
    }

    override suspend fun startTask(request: StartTaskRequest): Task {
        val task = kanbanService.getTask(request.taskId)
        return kanbanService.moveTask(
            taskId = task.id,
            toStatus = "IN_PROGRESS",
            assignee = request.assignee ?: task.assignee,
            operator = request.operator,
            comment = "Started task #${task.id}",
        )
    }

    override suspend fun commitTask(
        request: CommitTaskRequest,
        workingDir: File?,
    ): CommitTaskResult {
        val dir = workingDir ?: File(".")
        val executedHooks = mutableListOf<CommandExecutionResult>()

        val preHooks = config.hooks["pre-commit"] ?: emptyList()
        for (hook in preHooks) {
            val res = shellCommandRunner.execute(hook, dir)
            executedHooks.add(res)
            if (!res.success) {
                throw IllegalStateException(
                    "Pre-commit hook failed: $hook (exit code ${res.exitCode}): ${res.stderr.ifBlank { res.stdout }}",
                )
            }
        }

        var commitHash: String? = null
        if (request.executeGitCommit && gitCommandRunner.isGitRepository(dir)) {
            gitCommandRunner.addFiles(request.files, dir)
            val commitRes = gitCommandRunner.commit(request.message, dir)
            if (commitRes.exitCode != 0 && !commitRes.stdout.contains("nothing to commit")) {
                throw IllegalStateException("Git commit failed: ${commitRes.stderr.ifBlank { commitRes.stdout }}")
            }
            commitHash = gitCommandRunner.getHeadCommitHash(dir)
        }

        kanbanService.addComment(
            taskId = request.taskId,
            operator = request.operator,
            comment = "Committed changes: ${request.message}",
            commitHash = commitHash,
        )

        val postHooks = config.hooks["post-commit"] ?: emptyList()
        for (hook in postHooks) {
            val res = shellCommandRunner.execute(hook, dir)
            executedHooks.add(res)
        }

        val updatedTask = kanbanService.getTask(request.taskId)
        return CommitTaskResult(
            task = updatedTask,
            commitHash = commitHash,
            message = "Committed changes for task #${request.taskId}",
            executedHooks = executedHooks,
        )
    }
}
