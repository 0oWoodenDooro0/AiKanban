package aikanban.cli.command.column

import aikanban.cli.CliContext
import aikanban.cli.renderer.JsonRenderer
import aikanban.service.exception.ColumnNotFoundException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.green

class ColumnUpdateCommand : CliktCommand(name = "update") {
    override fun help(context: Context): String = "Update properties of an existing board column"

    private val cliContext by requireObject<CliContext>()

    private val id by argument("id", help = "Column ID to update")
    private val name by option("-n", "--name", help = "New display name")
    private val order by option("-o", "--order", help = "New display order index").int()
    private val color by option("-c", "--color", help = "New hex color code")
    private val terminal by option("-t", "--terminal", help = "Set terminal/completion status (true/false)")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val existing = cliContext.service.getColumn(id) ?: throw ColumnNotFoundException(id)

        val isTerminalValue = terminal?.toBooleanStrictOrNull() ?: existing.isTerminal
        val updated =
            existing.copy(
                name = name ?: existing.name,
                order = order ?: existing.order,
                color = color ?: existing.color,
                isTerminal = isTerminalValue,
            )

        val result = cliContext.service.updateColumn(updated)

        if (isJson) {
            println(JsonRenderer.render(result))
        } else {
            cliContext.terminal.println(green("✓ Updated column '${result.name}' [${result.id}]"))
        }
    }
}
