package aikanban.cli.command

import aikanban.cli.CliContext
import aikanban.cli.renderer.HumanRenderer
import aikanban.cli.renderer.JsonRenderer
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.green

class MoveCommand : CliktCommand(name = "move") {
    override fun help(context: Context): String = "Move a task to another status column"

    private val cliContext by requireObject<CliContext>()

    private val id by argument("id", help = "Task ID to move").int()
    private val status by argument("status", help = "Target column status ID (e.g. TODO, IN_PROGRESS, REVIEW, DONE)")
    private val operator by option("-o", "--operator", help = "Operator moving the task").default("cli")
    private val comment by option("-c", "--comment", help = "Optional comment or rationale for the status transition")
    private val pr by option("--pr", "--github-pr", help = "Associated GitHub Pull Request URL")
    private val assignee by option("-a", "--assignee", help = "Update assignee during the status transition")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val task =
            cliContext.service.moveTask(
                taskId = id,
                toStatus = status,
                operator = operator,
                comment = comment,
                prUrl = pr,
                assignee = assignee,
            )

        if (isJson) {
            println(JsonRenderer.render(task))
        } else {
            cliContext.terminal.println(green("✓ Moved Task #${task.id} to ${HumanRenderer.formatStatus(task.status)} by @$operator"))
        }
    }
}
