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
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.green

class UpdateCommand : CliktCommand(name = "update") {
    override fun help(context: Context): String = "Update task fields and metadata"

    private val cliContext by requireObject<CliContext>()

    private val id by argument("id", help = "Task ID to update").int()
    private val title by option("--title", help = "New task title")
    private val description by option("-d", "--description", help = "New task description in Markdown")
    private val priority by option("-p", "--priority", help = "New task priority (LOW, MEDIUM, HIGH, URGENT)")
        .enum<TaskPriority>(ignoreCase = true)
    private val assignee by option("-a", "--assignee", help = "New assignee")
    private val tags by option("-t", "--tag", help = "Replacement tags (repeatable or comma-separated)").multiple()
    private val githubRepo by option("--repo", "--github-repo", help = "New GitHub repository")
    private val githubIssueUrl by option("--issue", "--github-issue", help = "New GitHub Issue URL")
    private val githubPrUrl by option("--pr", "--github-pr", help = "New GitHub PR URL")
    private val operator by option("-o", "--operator", help = "Operator making the update").default("cli")
    private val comment by option("-c", "--comment", help = "Optional comment explaining the update")
    private val noSync by option("--no-sync", help = "Skip synchronizing changes with remote issue tracker").flag(default = false)
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val parsedTags =
            if (tags.isNotEmpty()) {
                tags.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.toSet()
            } else {
                null
            }

        val updated =
            cliContext.service.updateTask(
                taskId = id,
                title = title,
                description = description,
                priority = priority,
                assignee = assignee,
                tags = parsedTags,
                githubRepo = githubRepo,
                githubIssueUrl = githubIssueUrl,
                githubPrUrl = githubPrUrl,
                operator = operator,
                comment = comment,
            )

        val hasRemoteLink = !updated.githubIssueUrl.isNullOrBlank() || !updated.githubRepo.isNullOrBlank()
        if (!noSync && (title != null || description != null) && hasRemoteLink) {
            try {
                kotlinx.coroutines.runBlocking {
                    cliContext.workflowService.syncTaskRemote(updated.id)
                }
            } catch (e: Exception) {
                // If remote sync fails, log or ignore if offline
            }
        }

        if (isJson) {
            println(JsonRenderer.render(updated))
        } else {
            cliContext.terminal.println(green("✓ Updated Task #${updated.id}: \"${updated.title}\""))
            HumanRenderer.renderTaskDetail(cliContext.terminal, updated)
        }
    }
}
