package aikanban.cli.command

import aikanban.cli.CliContext
import aikanban.cli.renderer.HumanRenderer
import aikanban.cli.renderer.JsonRenderer
import aikanban.model.TaskPriority
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.mordant.rendering.TextColors.green

class AddCommand : CliktCommand(name = "add") {
    override fun help(context: Context): String = "Create a new task on the kanban board"

    private val cliContext by requireObject<CliContext>()

    private val title by argument("title", help = "Task title")
    private val description by option("-d", "--description", help = "Detailed task description in Markdown").default("")
    private val priority by option("-p", "--priority", help = "Task priority (LOW, MEDIUM, HIGH, URGENT)")
        .enum<TaskPriority>(ignoreCase = true).default(TaskPriority.MEDIUM)
    private val assignee by option("-a", "--assignee", help = "Assigned user or AI agent name")
    private val tags by option("-t", "--tag", help = "Task tag (repeatable or comma-separated)").multiple()
    private val branch by option("-b", "--branch", help = "Dedicated Git branch name")
    private val status by option("-s", "--status", help = "Initial board column status").default("TODO")
    private val githubRepo by option("--repo", "--github-repo", help = "Associated GitHub repository (e.g. owner/repo)")
    private val githubIssueUrl by option("--issue", "--github-issue", help = "Associated GitHub Issue URL")
    private val operator by option("-o", "--operator", help = "Operator identifier").default("cli")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val parsedTags = tags.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.toSet()

        val task =
            cliContext.service.createTask(
                title = title,
                description = description,
                priority = priority,
                assignee = assignee,
                tags = parsedTags,
                branch = branch,
                githubRepo = githubRepo,
                githubIssueUrl = githubIssueUrl,
                status = status,
                operator = operator,
            )

        if (isJson) {
            println(JsonRenderer.render(task))
        } else {
            cliContext.terminal.println(green("✓ Created Task #${task.id}: \"${task.title}\""))
            HumanRenderer.renderTaskDetail(cliContext.terminal, task)
        }
    }
}
