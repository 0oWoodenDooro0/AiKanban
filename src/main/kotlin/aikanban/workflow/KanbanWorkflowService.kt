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
    val pushBranch: Boolean = false,
    val noCheckout: Boolean = false,
    val fromPlanFile: String? = null,
)

@Serializable
data class StartIssueResult(
    val task: Task,
    val issue: IssueResult,
    val branch: BranchResult,
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

@Serializable
data class RequestChangesWorkflowResult(
    val task: Task,
    val comment: String,
    val prUrl: String?,
)

@Serializable
data class CompleteReviewWorkflowRequest(
    val taskId: Int,
    val merge: Boolean = false,
    val comment: String? = null,
    val operator: String = "workflow",
    val providerName: String? = null,
)

@Serializable
data class CompleteReviewWorkflowResult(
    val task: Task,
    val merged: Boolean,
    val prUrl: String?,
    val executedHooks: List<CommandExecutionResult> = emptyList(),
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
    suspend fun startIssue(
        request: StartIssueRequest,
        workingDir: File? = null,
    ): StartIssueResult

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

        data class PlanMetadata(
            val title: String?,
            val description: String?,
            val tags: Set<String>,
            val priority: TaskPriority?,
        )

        fun extractPlanMetadata(content: String): PlanMetadata {
            val lines = content.lines()
            var title: String? = null
            val tags = mutableSetOf<String>()
            var priority: TaskPriority? = null
            val descLines = mutableListOf<String>()

            for (line in lines) {
                val trimmed = line.trim()
                if (title == null && trimmed.startsWith("#") && !trimmed.startsWith("##")) {
                    val rawTitle = trimmed.removePrefix("#").trim()
                    title = rawTitle
                    val match = Regex("""^(\w+)(?:\(([^)]+)\))?:""").find(rawTitle)
                    if (match != null) {
                        val scope = match.groupValues.getOrNull(2)?.trim()
                        if (!scope.isNullOrBlank()) {
                            tags.add(scope)
                        }
                    }
                } else if (title != null) {
                    descLines.add(line)
                }
            }
            return PlanMetadata(
                title = title,
                description = descLines.joinToString("\n").trim().takeIf { it.isNotBlank() },
                tags = tags,
                priority = priority,
            )
        }
    }

    override suspend fun startIssue(
        request: StartIssueRequest,
        workingDir: File?,
    ): StartIssueResult {
        val dir = workingDir ?: File(".")
        val provider = providerOverride ?: providerFactory.resolve(request.providerName, config)

        var effectiveTitle = request.title.trim()
        var effectiveDesc = request.description.trim()
        val effectiveTags = request.tags.toMutableSet()
        var effectivePlan = request.plan

        if (!request.fromPlanFile.isNullOrBlank()) {
            val planFile = File(request.fromPlanFile)
            val planContent = if (planFile.isFile && planFile.canRead()) planFile.readText() else request.fromPlanFile
            effectivePlan = planContent
            val meta = extractPlanMetadata(planContent)
            if (effectiveTitle.isBlank() && !meta.title.isNullOrBlank()) {
                effectiveTitle = meta.title
            }
            if (effectiveDesc.isBlank() && !meta.description.isNullOrBlank()) {
                effectiveDesc = meta.description
            }
            effectiveTags.addAll(meta.tags)
        } else if (effectiveTitle.isBlank() && !effectivePlan.isNullOrBlank()) {
            val meta = extractPlanMetadata(effectivePlan)
            if (!meta.title.isNullOrBlank()) {
                effectiveTitle = meta.title
            }
            if (effectiveDesc.isBlank() && !meta.description.isNullOrBlank()) {
                effectiveDesc = meta.description
            }
            effectiveTags.addAll(meta.tags)
        }

        if (effectiveTitle.isBlank()) {
            effectiveTitle = "Untitled Task"
        }

        val branchName =
            request.branchName?.trim()?.takeIf { it.isNotBlank() }
                ?: "${config.branchPrefix}${slugify(effectiveTitle)}"

        val issueResult =
            provider.createIssue(
                CreateIssueRequest(
                    title = effectiveTitle,
                    body = effectiveDesc,
                    labels = effectiveTags,
                    priority = request.priority,
                    assignee = request.assignee,
                ),
            )

        if (request.dryRun) {
            val previewTask =
                Task(
                    id = 0,
                    title = effectiveTitle,
                    description = effectiveDesc,
                    priority = request.priority,
                    assignee = request.assignee,
                    tags = effectiveTags,
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
                title = effectiveTitle,
                description = effectiveDesc,
                priority = request.priority,
                assignee = request.assignee,
                tags = effectiveTags,
                githubIssueUrl = issueResult.url,
                status = "TODO",
                operator = request.operator,
            )

        if (!effectivePlan.isNullOrBlank()) {
            provider.addComment(
                AddIssueCommentRequest(
                    issueIdOrUrl = issueResult.url ?: issueResult.id,
                    comment = effectivePlan,
                ),
            )
            kanbanService.addComment(
                taskId = task.id,
                operator = request.operator,
                comment = "Attached implementation plan",
            )
        }

        val originalBranch =
            if (request.noCheckout && gitCommandRunner.isGitRepository(dir)) {
                gitCommandRunner.getCurrentBranch(dir)
            } else {
                null
            }

        val branchResult =
            provider.createBranch(
                CreateBranchRequest(
                    branchName = branchName,
                    baseBranch = request.baseBranch,
                    issueIdOrUrl = issueResult.url,
                ),
            )

        if (request.pushBranch && gitCommandRunner.isGitRepository(dir)) {
            gitCommandRunner.pushBranch(branchName, "origin", true, dir)
        }

        if (request.noCheckout && originalBranch != null) {
            gitCommandRunner.checkoutBranch(originalBranch, workingDir = dir)
        }

        kanbanService.addComment(
            taskId = task.id,
            operator = request.operator,
            comment = "Created and switched to branch $branchName",
        )

        return StartIssueResult(
            task = kanbanService.getTask(task.id),
            issue = issueResult,
            branch = branchResult,
        )
    }

    override suspend fun submitPr(request: SubmitPrRequest): SubmitPrResult {
        val provider = providerFactory.resolve(request.providerName, config)
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
        val reviewColumn = config.workflow.reviewColumn.ifBlank { "REVIEW" }

        val task =
            if (request.taskId != null) {
                kanbanService.getTask(request.taskId)
            } else {
                val candidateTasks = kanbanService.listTasks(status = reviewColumn)
                candidateTasks.minWithOrNull(
                    compareBy<Task> { task ->
                        when (task.priority) {
                            TaskPriority.URGENT -> 1
                            TaskPriority.HIGH -> 2
                            TaskPriority.MEDIUM -> 3
                            TaskPriority.LOW -> 4
                        }
                    }.thenBy { it.createdAt },
                ) ?: throw aikanban.service.exception.KanbanException("No tasks found in $reviewColumn column")
            }

        val executedHooks = mutableListOf<CommandExecutionResult>()
        val preHooks = config.hooks["pre-start-review"] ?: emptyList()
        for (hook in preHooks) {
            val res = shellCommandRunner.execute(hook, dir)
            executedHooks.add(res)
            if (!res.success) {
                throw IllegalStateException(
                    "Pre-start-review hook failed: $hook (exit code ${res.exitCode}): ${res.stderr.ifBlank { res.stdout }}",
                )
            }
        }

        var targetBranch: String? = null
        for (log in task.logs.reversed()) {
            val prefix = "Created and switched to branch "
            if (log.comment.startsWith(prefix)) {
                targetBranch = log.comment.removePrefix(prefix).trim()
                break
            }
        }

        if (targetBranch == null && task.githubPrUrl != null && task.githubPrUrl.startsWith("local://pull/")) {
            targetBranch = task.githubPrUrl.removePrefix("local://pull/")
        }

        if (targetBranch == null) {
            targetBranch = "${config.branchPrefix}${slugify(task.title)}"
        }

        if (request.checkoutBranch && targetBranch != null && gitCommandRunner.isGitRepository(dir)) {
            gitCommandRunner.checkoutBranch(targetBranch, workingDir = dir)
        }

        kanbanService.addComment(
            taskId = task.id,
            operator = request.operator,
            comment = "Started code review for task #${task.id}",
        )

        val postHooks = config.hooks["post-start-review"] ?: emptyList()
        for (hook in postHooks) {
            val res = shellCommandRunner.execute(hook, dir)
            executedHooks.add(res)
        }

        val updatedTask = kanbanService.getTask(task.id)
        return StartReviewResult(
            task = updatedTask,
            branchName = targetBranch,
            prUrl = task.githubPrUrl,
            executedHooks = executedHooks,
        )
    }

    override suspend fun requestChanges(request: RequestChangesWorkflowRequest): RequestChangesWorkflowResult {
        val provider = providerOverride ?: providerFactory.resolve(request.providerName, config)
        val task = kanbanService.getTask(request.taskId)
        val targetColumn = config.workflow.requestColumn.ifBlank { "REQUEST" }

        val updatedTask =
            kanbanService.moveTask(
                taskId = task.id,
                toStatus = targetColumn,
                operator = request.operator,
                comment = request.comment,
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

        val prUrl = task.githubPrUrl
        if (request.merge && !prUrl.isNullOrBlank()) {
            provider.mergePullRequest(
                MergePullRequestRequest(
                    prNumberOrUrl = prUrl,
                    mergeMethod = config.workflow.mergeMethod,
                    deleteBranch = config.workflow.deleteBranchOnMerge,
                ),
            )
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
