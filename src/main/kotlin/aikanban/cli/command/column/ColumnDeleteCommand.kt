package aikanban.cli.command.column

import aikanban.cli.CliContext
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.green

class ColumnDeleteCommand : CliktCommand(name = "delete") {
    override fun help(context: Context): String = "Delete a board column"

    private val cliContext by requireObject<CliContext>()

    private val id by argument("id", help = "Column ID to delete")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val success = cliContext.service.deleteColumn(id)

        if (isJson) {
            println("""{"deleted": $success, "id": "$id"}""")
        } else {
            if (success) {
                cliContext.terminal.println(green("✓ Deleted column [$id]"))
            } else {
                cliContext.terminal.println("Column [$id] was not deleted.")
            }
        }
    }
}
