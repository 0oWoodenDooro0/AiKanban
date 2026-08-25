package aikanban.cli.command.column

import aikanban.cli.CliContext
import aikanban.cli.renderer.HumanRenderer
import aikanban.cli.renderer.JsonRenderer
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class ColumnListCommand : CliktCommand(name = "list") {
    override fun help(context: Context): String = "List all board columns in display order"

    private val cliContext by requireObject<CliContext>()
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val columns = cliContext.service.getColumns()

        if (isJson) {
            println(JsonRenderer.render(columns))
        } else {
            HumanRenderer.renderColumnList(cliContext.terminal, columns)
        }
    }
}
