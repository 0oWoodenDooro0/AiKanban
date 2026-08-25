package aikanban.cli.command

import aikanban.cli.CliContext
import aikanban.cli.renderer.HumanRenderer
import aikanban.cli.renderer.JsonRenderer
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

class ShowCommand : CliktCommand(name = "show") {
    override fun help(context: Context): String = "Display full details and activity history of a task"

    private val cliContext by requireObject<CliContext>()

    private val id by argument("id", help = "Task ID").int()
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val task = cliContext.service.getTask(id)

        if (isJson) {
            println(JsonRenderer.render(task))
        } else {
            HumanRenderer.renderTaskDetail(cliContext.terminal, task)
        }
    }
}
