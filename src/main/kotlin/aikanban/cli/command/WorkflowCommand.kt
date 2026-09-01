package aikanban.cli.command

import aikanban.cli.CliContext
import aikanban.cli.renderer.HumanRenderer
import aikanban.cli.renderer.JsonRenderer
import aikanban.model.TaskPriority
import aikanban.workflow.CommitTaskRequest
import aikanban.workflow.CompleteReviewWorkflowRequest
import aikanban.workflow.RequestChangesWorkflowRequest
import aikanban.workflow.StartIssueRequest
import aikanban.workflow.StartReviewRequest
import aikanban.workflow.StartTaskRequest
import aikanban.workflow.SubmitPrRequest
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import kotlinx.coroutines.runBlocking
import java.io.File

class WorkflowCommand : CliktCommand(name = "workflow") {
    override fun help(context: Context): String = "Automate multi-step Issue, Git branch, and Kanban development workflows"

    init {
        subcommands(
            WorkflowStartIssueCommand(),
            WorkflowSubmitPrCommand(),
            WorkflowStartReviewCommand(),
            WorkflowRequestChangesCommand(),
            WorkflowCompleteReviewCommand(),
            WorkflowRunCommand(),
            WorkflowVerifyCommand(),
            WorkflowStartTaskCommand(),
            WorkflowCommitCommand(),
        )
    }

    override fun run() = Unit
}

class WorkflowStartIssueCommand : CliktCommand(name = "start-issue") {
    override fun help(context: Context): String = "Atomically create issue, Kanban task, plan comment, and dedicated Git development branch"

    private val cliContext by requireObject<CliContext>()

    private val title by argument("title", help = "Issue and task title")
    private val description by option("-d", "--description", help = "Task description in Markdown").default("")
    private val priority by option("-p", "--priority", help = "Task priority (LOW, MEDIUM, HIGH, URGENT)")
        .enum<TaskPriority>(ignoreCase = true).default(TaskPriority.MEDIUM)
    private val tags by option("-t", "--tag", help = "Task tag (repeatable or comma-separated)").multiple()
    private val branch by option("-b", "--branch", help = "Dedicated Git branch name (auto-generated if omitted)")
    private val base by option("--base", help = "Base branch for new branch (defaults to config defaultBaseBranch or 'main')")
    private val plan by option("--plan", help = "Implementation plan markdown text or file path")
    private val assignee by option("-a", "--assignee", help = "Assigned user or agent name")
    private val operator by option("-o", "--operator", help = "Operator identifier")
    private val provider by option("--provider", help = "VCS provider override (local-git, github)")
    private val dryRun by option("--dry-run", help = "Preview workflow actions without modifying database or git").flag(default = false)
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val parsedTags = tags.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val effectiveOperator = cliContext.resolveOperator(operator)

        val activeConfig = cliContext.ensureConfig(overrideProvider = provider, isJson = isJson)

        val planText =
            plan?.let {
                val file = File(it)
                if (file.isFile && file.canRead()) file.readText() else it
            }

        val targetBase = base ?: activeConfig.defaultBaseBranch

        val request =
            StartIssueRequest(
                title = title,
                description = description,
                priority = priority,
                tags = parsedTags,
                branchName = branch,
                baseBranch = targetBase,
                plan = planText,
                assignee = assignee,
                operator = effectiveOperator,
                providerName = provider ?: activeConfig.provider,
                dryRun = dryRun,
            )

        val result = runBlocking { cliContext.workflowService.startIssue(request) }

        if (isJson) {
            println(JsonRenderer.render(result))
        } else {
            val t = cliContext.terminal
            val prefix = if (dryRun) yellow("[DRY RUN] ") else ""
            t.println(bold(green("$prefix✓ Started workflow for Task #${result.task.id}: \"${result.task.title}\"")))
            if (result.issue.url != null) {
                t.println(cyan("  • Issue: ${result.issue.url}"))
            }
            t.println(blue("  • Branch: ${result.branch.branchName} (based on ${result.branch.baseBranch})"))
            if (!dryRun) {
                HumanRenderer.renderTaskDetail(t, result.task)
            }
        }
    }
}

class WorkflowSubmitPrCommand : CliktCommand(name = "submit-pr") {
    override fun help(context: Context): String =
        "Atomically push Git branch, create Pull Request, attach PR URL, and transition task to REVIEW"

    private val cliContext by requireObject<CliContext>()

    private val taskId by argument("taskId", help = "Kanban Task ID").int()
    private val title by option("--title", help = "Pull Request title (defaults to task title)")
    private val body by option("--body", help = "Pull Request body in Markdown")
    private val bodyFile by option("--body-file", help = "Path to file containing Pull Request body")
    private val head by option("--head", help = "Head branch to submit (defaults to current Git branch)")
    private val base by option("--base", help = "Base branch to merge into (defaults to config defaultBaseBranch or 'main')")
    private val draft by option("--draft", help = "Open Pull Request as a draft").flag(default = false)
    private val operator by option("-o", "--operator", help = "Operator identifier")
    private val provider by option("--provider", help = "VCS provider override (local-git, github)")
    private val dryRun by option("--dry-run", help = "Preview PR submission without modifying database or git").flag(default = false)
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val effectiveOperator = cliContext.resolveOperator(operator)

        val activeConfig = cliContext.ensureConfig(overrideProvider = provider, isJson = isJson)

        val prBody =
            bodyFile?.let {
                val file = File(it)
                if (file.isFile && file.canRead()) file.readText() else null
            } ?: body

        val targetBase = base ?: activeConfig.defaultBaseBranch

        val request =
            SubmitPrRequest(
                taskId = taskId,
                title = title,
                body = prBody,
                headBranch = head,
                baseBranch = targetBase,
                draft = draft,
                operator = effectiveOperator,
                providerName = provider ?: activeConfig.provider,
                dryRun = dryRun,
            )

        val result = runBlocking { cliContext.workflowService.submitPr(request) }

        if (isJson) {
            println(JsonRenderer.render(result))
        } else {
            val t = cliContext.terminal
            val prefix = if (dryRun) yellow("[DRY RUN] ") else ""
            t.println(bold(green("$prefix✓ Submitted PR for Task #${result.task.id}: ${result.pr.url}")))
            t.println(cyan("  • Status: ${result.task.status}"))
            t.println(blue("  • Branch: ${result.pr.headBranch} -> ${result.pr.baseBranch}"))
        }
    }
}

class WorkflowStartReviewCommand : CliktCommand(name = "start-review") {
    override fun help(context: Context): String =
        "Start code review: resolve REVIEW task, execute pre/post review hooks, and checkout feature branch"

    private val cliContext by requireObject<CliContext>()

    private val taskId by argument("taskId", help = "Task ID (defaults to top task in REVIEW column)").int().optional()
    private val noCheckout by option("--no-checkout", help = "Skip checking out feature branch locally").flag(default = false)
    private val stash by option(
        "--stash",
        help = "Automatically stash uncommitted changes before checking out branch",
    ).flag(default = false)
    private val force by option(
        "-f",
        "--force",
        help = "Force branch checkout even if working tree has uncommitted changes",
    ).flag(default = false)
    private val operator by option("-o", "--operator", help = "Operator identifier")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val effectiveOperator = cliContext.resolveOperator(operator)
        val result =
            runBlocking {
                cliContext.workflowService.startReview(
                    StartReviewRequest(
                        taskId = taskId,
                        operator = effectiveOperator,
                        checkoutBranch = !noCheckout,
                        stash = stash,
                        force = force,
                    ),
                    workingDir = cliContext.workingDir,
                )
            }

        if (isJson) {
            println(JsonRenderer.render(result))
        } else {
            val t = cliContext.terminal
            t.println(bold(green("✓ Started review for Task #${result.task.id}: \"${result.task.title}\"")))
            if (result.stashed) {
                t.println(yellow("  • Uncommitted changes stashed automatically"))
            }
            if (result.branchName != null) {
                t.println(blue("  • Branch: ${result.branchName}"))
            }
            if (result.prUrl != null) {
                t.println(cyan("  • PR: ${result.prUrl}"))
            }
            HumanRenderer.renderTaskDetail(t, result.task)
        }
    }
}

class WorkflowRequestChangesCommand : CliktCommand(name = "request-changes") {
    override fun help(context: Context): String =
        "Request changes on task: transition to REQUEST column, submit PR review request, and log comments"

    private val cliContext by requireObject<CliContext>()

    private val taskId by argument("taskId", help = "Task ID").int()
    private val message by option("-m", "--message", help = "Review comments or rework requirements").default("")
    private val bodyFile by option("--body-file", help = "Path to file containing review comments")
    private val operator by option("-o", "--operator", help = "Operator identifier")
    private val provider by option("--provider", help = "VCS provider override (local-git, github)")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val reviewComment =
            bodyFile?.let {
                val file = File(it)
                if (file.isFile && file.canRead()) file.readText() else null
            } ?: message.ifBlank { "Changes requested by reviewer" }

        val effectiveOperator = cliContext.resolveOperator(operator)
        val result =
            runBlocking {
                cliContext.workflowService.requestChanges(
                    RequestChangesWorkflowRequest(
                        taskId = taskId,
                        comment = reviewComment,
                        operator = effectiveOperator,
                        providerName = provider,
                    ),
                )
            }

        if (isJson) {
            println(JsonRenderer.render(result))
        } else {
            val t = cliContext.terminal
            t.println(bold(yellow("✓ Requested changes for Task #${result.task.id}: \"${result.task.title}\"")))
            t.println(cyan("  • Status: ${result.task.status}"))
            t.println("  • Feedback: ${result.comment}")
        }
    }
}

class WorkflowCompleteReviewCommand : CliktCommand(name = "complete-review") {
    override fun help(context: Context): String =
        "Complete review: run quality verification gate, execute lifecycle hooks, " +
            "optionally merge PR / branch, checkout base branch, and delete feature branch"

    private val cliContext by requireObject<CliContext>()

    private val taskId by argument("taskId", help = "Task ID").int()
    private val merge by option("--merge", help = "Automatically approve and merge PR / feature branch").flag(default = false)
    private val message by option("-m", "--message", help = "Approval summary or audit comment")
    private val verify by option("--verify", help = "Run quality verification checks before completing review").flag(default = false)
    private val checkoutBase by option(
        "--checkout-base",
        help = "Checkout target base branch after review completion",
    ).flag("--no-checkout-base", default = true)
    private val deleteBranch by option("--delete-branch", help = "Delete feature branch locally upon completion").flag(default = false)
    private val noDeleteBranch by option("--no-delete-branch", help = "Skip deleting feature branch locally").flag(default = false)
    private val pullBase by option("--pull-base", help = "Execute git pull on target base branch after checkout").flag(default = false)
    private val base by option("--base", help = "Target base branch override (defaults to config defaultBaseBranch or 'main')")
    private val operator by option("-o", "--operator", help = "Operator identifier")
    private val provider by option("--provider", help = "VCS provider override (local-git, github)")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val effectiveOperator = cliContext.resolveOperator(operator)
        val effectiveDeleteBranch: Boolean? =
            if (deleteBranch) {
                true
            } else if (noDeleteBranch) {
                false
            } else {
                null
            }

        val result =
            runBlocking {
                cliContext.workflowService.completeReview(
                    CompleteReviewWorkflowRequest(
                        taskId = taskId,
                        merge = merge,
                        comment = message,
                        operator = effectiveOperator,
                        providerName = provider,
                        verify = verify,
                        checkoutBase = checkoutBase,
                        deleteBranch = effectiveDeleteBranch,
                        pullBase = pullBase,
                        targetBaseBranch = base,
                    ),
                    workingDir = cliContext.workingDir,
                )
            }

        if (isJson) {
            println(JsonRenderer.render(result))
        } else {
            val t = cliContext.terminal
            val mergeInfo = if (result.merged) " (Merged)" else ""
            t.println(bold(green("✓ Completed review for Task #${result.task.id}$mergeInfo: \"${result.task.title}\"")))
            t.println(cyan("  • Status: ${result.task.status}"))
            if (result.verificationPassed == true) {
                t.println(green("  • Quality verification: Passed"))
            }
            if (result.baseBranch != null) {
                t.println(blue("  • Checked out base branch: ${result.baseBranch}"))
            }
            if (result.deletedBranch != null) {
                t.println(yellow("  • Deleted feature branch: ${result.deletedBranch}"))
            }
            if (result.executedHooks.isNotEmpty()) {
                t.println("  • Executed ${result.executedHooks.size} lifecycle hook(s)")
            }
        }
    }
}

class WorkflowRunCommand : CliktCommand(name = "run") {
    override fun help(context: Context): String = "Execute custom defined workflow steps from configuration"

    private val cliContext by requireObject<CliContext>()

    private val name by argument("name", help = "Workflow name defined in configuration")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val result = runBlocking { cliContext.workflowService.runWorkflow(name, cliContext.workingDir) }

        if (isJson) {
            println(JsonRenderer.render(result))
        } else {
            val t = cliContext.terminal
            if (result.success) {
                t.println(bold(green("✓ Workflow '$name' completed successfully (${result.executedSteps.size} steps)")))
            } else {
                t.println(bold(red("✗ Workflow '$name' failed: ${result.message}")))
            }
            for (step in result.executedSteps) {
                val icon = if (step.success) green("✓") else red("✗")
                t.println("  $icon ${step.step} (exit ${step.exitCode})")
                if (!step.success && step.stderr.isNotBlank()) {
                    t.println(yellow("    ${step.stderr.lines().take(5).joinToString("\n    ")}"))
                }
            }
        }
    }
}

class WorkflowVerifyCommand : CliktCommand(name = "verify") {
    override fun help(context: Context): String = "Run project verification and quality checks configured in .aikanban.json"

    private val cliContext by requireObject<CliContext>()

    private val commands by option("-c", "--command", help = "Specific verification command override (repeatable)").multiple()
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val customCmds = commands.takeIf { it.isNotEmpty() }
        val result = runBlocking { cliContext.workflowService.runVerify(customCmds, cliContext.workingDir) }

        if (isJson) {
            println(JsonRenderer.render(result))
        } else {
            val t = cliContext.terminal
            if (result.success) {
                t.println(bold(green("✓ ${result.message}")))
            } else {
                t.println(bold(red("✗ ${result.message}")))
            }
            for (cmd in result.executedCommands) {
                val icon = if (cmd.success) green("✓") else red("✗")
                t.println("  $icon ${cmd.command} (exit ${cmd.exitCode})")
                if (!cmd.success && cmd.stderr.isNotBlank()) {
                    t.println(yellow("    ${cmd.stderr.lines().take(5).joinToString("\n    ")}"))
                }
            }
        }
    }
}

class WorkflowStartTaskCommand : CliktCommand(name = "start-task") {
    override fun help(context: Context): String = "Atomically start working on an existing task and move it to IN_PROGRESS"

    private val cliContext by requireObject<CliContext>()

    private val taskId by argument("taskId", help = "Task ID").int()
    private val assignee by option("-a", "--assignee", help = "Assigned user or agent name")
    private val noCheckout by option("--no-checkout", help = "Skip checking out feature branch locally").flag(default = false)
    private val operator by option("-o", "--operator", help = "Operator identifier")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val effectiveOperator = cliContext.resolveOperator(operator)
        val task =
            runBlocking {
                cliContext.workflowService.startTask(
                    StartTaskRequest(
                        taskId = taskId,
                        assignee = assignee,
                        checkoutBranch = !noCheckout,
                        operator = effectiveOperator,
                    ),
                    workingDir = cliContext.workingDir,
                )
            }

        if (isJson) {
            println(JsonRenderer.render(task))
        } else {
            val t = cliContext.terminal
            t.println(bold(green("✓ Started Task #${task.id}: \"${task.title}\" (Status: ${task.status})")))
            if (task.branch != null) {
                t.println(blue("  • Branch: ${task.branch}"))
            }
            HumanRenderer.renderTaskDetail(t, task)
        }
    }
}

class WorkflowCommitCommand : CliktCommand(name = "commit") {
    override fun help(context: Context): String =
        "Execute pre-commit hooks, stage & commit changes to Git, and log commit hash to AiKanban task"

    private val cliContext by requireObject<CliContext>()

    private val taskId by argument("taskId", help = "Task ID").int()
    private val message by option("-m", "--message", help = "Git commit message and audit log summary").required()
    private val files by option("-f", "--file", help = "Specific files to stage (defaults to all changed files)").multiple()
    private val noGit by option("--no-git", help = "Skip local git commit execution, only log to AiKanban").flag(default = false)
    private val operator by option("-o", "--operator", help = "Operator identifier")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val effectiveOperator = cliContext.resolveOperator(operator)
        val result =
            runBlocking {
                cliContext.workflowService.commitTask(
                    CommitTaskRequest(
                        taskId = taskId,
                        message = message,
                        files = files,
                        operator = effectiveOperator,
                        executeGitCommit = !noGit,
                    ),
                    workingDir = cliContext.workingDir,
                )
            }

        if (isJson) {
            println(JsonRenderer.render(result))
        } else {
            val t = cliContext.terminal
            val commitInfo = result.commitHash?.take(7)?.let { " [$it]" } ?: ""
            t.println(bold(green("✓ Committed for Task #${result.task.id}$commitInfo: \"$message\"")))
            if (result.executedHooks.isNotEmpty()) {
                t.println(cyan("  • Executed ${result.executedHooks.size} lifecycle hook(s)"))
            }
        }
    }
}
