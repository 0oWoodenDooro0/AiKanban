package aikanban.cli.command

import aikanban.cli.CliContext
import aikanban.cli.renderer.HumanRenderer
import aikanban.cli.renderer.JsonRenderer
import aikanban.model.TaskPriority
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum

class ListCommand : CliktCommand(name = "list") {
    override fun help(context: Context): String = "List and filter tasks on the board"

    private val cliContext by requireObject<CliContext>()

    private val status by option("-s", "--status", help = "Filter tasks by status column (e.g. TODO, IN_PROGRESS)")
    private val assignee by option("-a", "--assignee", help = "Filter tasks by assignee name")
    private val tag by option("-t", "--tag", help = "Filter tasks by tag")
    private val priority by option("-p", "--priority", help = "Filter tasks by priority (LOW, MEDIUM, HIGH, URGENT)")
        .enum<TaskPriority>(ignoreCase = true)
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val tasks =
            cliContext.service.listTasks(
                status = status,
                assignee = assignee,
                tag = tag,
                priority = priority,
            )

        if (isJson) {
            println(JsonRenderer.render(tasks))
        } else {
            val filters =
                listOfNotNull(
                    status?.let { "status: $it" },
                    assignee?.let { "assignee: @$it" },
                    tag?.let { "tag: #$it" },
                    priority?.let { "priority: $it" },
                ).joinToString(", ").ifBlank { null }
            HumanRenderer.renderTaskList(cliContext.terminal, tasks, filters)
        }
    }
}
