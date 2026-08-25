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

class LogCommand : CliktCommand(name = "log") {
    override fun help(context: Context): String = "View audit logs or append a comment/progress entry to a task"

    private val cliContext by requireObject<CliContext>()

    private val id by argument("id", help = "Task ID").int()
    private val comment by option("-m", "-c", "--comment", "--message", help = "Comment or progress log message to add")
    private val operator by option("-o", "--operator", help = "Operator adding the log").default("cli")
    private val pr by option("--pr", "--github-pr", help = "Associated GitHub Pull Request URL")
    private val commit by option("--commit", help = "Associated Git commit hash")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val logComment = comment

        if (logComment != null) {
            val entry =
                cliContext.service.addComment(
                    taskId = id,
                    operator = operator,
                    comment = logComment,
                    prUrl = pr,
                    commitHash = commit,
                )

            if (isJson) {
                println(JsonRenderer.render(entry))
            } else {
                cliContext.terminal.println(green("✓ Added comment to Task #$id by @$operator: \"$logComment\""))
            }
        } else {
            val logs = cliContext.service.getTaskLogs(id)
            if (isJson) {
                println(JsonRenderer.render(logs))
            } else {
                HumanRenderer.renderTaskLogs(cliContext.terminal, logs)
            }
        }
    }
}
