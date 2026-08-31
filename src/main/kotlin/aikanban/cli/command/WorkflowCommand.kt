package aikanban.cli.command

import aikanban.cli.CliContext
import aikanban.cli.renderer.HumanRenderer
import aikanban.cli.renderer.JsonRenderer
import aikanban.config.AiKanbanConfigLoader
import aikanban.model.TaskPriority
import aikanban.workflow.StartIssueRequest
import aikanban.workflow.SubmitPrRequest
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
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
    private val operator by option("-o", "--operator", help = "Operator identifier").default("workflow")
    private val provider by option("--provider", help = "VCS provider override (local-git, github)")
    private val dryRun by option("--dry-run", help = "Preview workflow actions without modifying database or git").flag(default = false)
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val parsedTags = tags.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.toSet()

        val activeConfig =
            if (provider == null && !isJson && !AiKanbanConfigLoader.hasConfigFile(cliContext.workingDir)) {
                AiKanbanConfigLoader.ensureProviderConfig(
                    workingDir = cliContext.workingDir,
                    prompter = cliContext.prompter,
                    gitCommandRunner = cliContext.gitCommandRunner,
                )
            } else {
                cliContext.config
            }

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
                operator = operator,
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
    private val operator by option("-o", "--operator", help = "Operator identifier").default("workflow")
    private val provider by option("--provider", help = "VCS provider override (local-git, github)")
    private val dryRun by option("--dry-run", help = "Preview PR submission without modifying database or git").flag(default = false)
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput

        val activeConfig =
            if (provider == null && !isJson && !AiKanbanConfigLoader.hasConfigFile(cliContext.workingDir)) {
                AiKanbanConfigLoader.ensureProviderConfig(
                    workingDir = cliContext.workingDir,
                    prompter = cliContext.prompter,
                    gitCommandRunner = cliContext.gitCommandRunner,
                )
            } else {
                cliContext.config
            }

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
                operator = operator,
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
