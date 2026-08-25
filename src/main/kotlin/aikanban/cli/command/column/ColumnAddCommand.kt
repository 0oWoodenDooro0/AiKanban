package aikanban.cli.command.column

import aikanban.cli.CliContext
import aikanban.cli.renderer.JsonRenderer
import aikanban.model.BoardColumn
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.green

class ColumnAddCommand : CliktCommand(name = "add") {
    override fun help(context: Context): String = "Add a new board column"

    private val cliContext by requireObject<CliContext>()

    private val id by argument("id", help = "Unique column ID (e.g. QA, DEPLOY)")
    private val name by argument("name", help = "Display name of the column")
    private val order by option("-o", "--order", help = "Display order index").int().default(0)
    private val color by option("-c", "--color", help = "Hex color code (e.g. #3B82F6)").default("#6B7280")
    private val terminal by option("-t", "--terminal", help = "Mark column as terminal/completion state").flag(default = false)
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val column =
            BoardColumn(
                id = id,
                name = name,
                order = order,
                color = color,
                isTerminal = terminal,
            )
        val created = cliContext.service.createColumn(column)

        if (isJson) {
            println(JsonRenderer.render(created))
        } else {
            cliContext.terminal.println(
                green("✓ Created column '${created.name}' [${created.id}] (order: ${created.order}, color: ${created.color})"),
            )
        }
    }
}
