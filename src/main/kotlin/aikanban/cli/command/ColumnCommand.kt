package aikanban.cli.command

import aikanban.cli.CliContext
import aikanban.cli.command.column.ColumnAddCommand
import aikanban.cli.command.column.ColumnDeleteCommand
import aikanban.cli.command.column.ColumnListCommand
import aikanban.cli.command.column.ColumnUpdateCommand
import aikanban.cli.renderer.HumanRenderer
import aikanban.cli.renderer.JsonRenderer
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class ColumnCommand : CliktCommand(name = "column") {
    override fun help(context: Context): String = "Manage kanban board columns (list, add, update, delete)"

    override val invokeWithoutSubcommand: Boolean get() = true

    private val cliContext by requireObject<CliContext>()
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    init {
        subcommands(
            ColumnListCommand(),
            ColumnAddCommand(),
            ColumnUpdateCommand(),
            ColumnDeleteCommand(),
        )
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            val isJson = json || cliContext.jsonOutput
            val columns = cliContext.service.getColumns()
            if (isJson) {
                println(JsonRenderer.render(columns))
            } else {
                HumanRenderer.renderColumnList(cliContext.terminal, columns)
            }
        }
    }
}
