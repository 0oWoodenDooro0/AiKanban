package aikanban.cli

import aikanban.cli.command.AddCommand
import aikanban.cli.command.ClaimCommand
import aikanban.cli.command.ColumnCommand
import aikanban.cli.command.ListCommand
import aikanban.cli.command.LogCommand
import aikanban.cli.command.MoveCommand
import aikanban.cli.command.ShowCommand
import aikanban.cli.command.UpdateCommand
import aikanban.cli.renderer.JsonRenderer
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import aikanban.service.exception.KanbanException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal

class AiKanbanCommand(
    private val serviceOverride: KanbanService? = null,
    private val terminal: Terminal = Terminal(),
) : CliktCommand(name = "aikanban") {
    override fun help(context: Context): String = "AiKanban - Universal CLI Kanban Board for Humans and AI Agents"

    val db by option("--db", help = "Path to SQLite database file", envvar = "AIKANBAN_DB")
        .default("aikanban.db")
    val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    init {
        subcommands(
            ListCommand(),
            AddCommand(),
            ShowCommand(),
            MoveCommand(),
            ClaimCommand(),
            LogCommand(),
            UpdateCommand(),
            ColumnCommand(),
        )
    }

    override fun run() {
        val service = serviceOverride ?: DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:$db"))
        currentContext.findOrSetObject {
            CliContext(
                service = service,
                terminal = terminal,
                jsonOutput = json,
            )
        }
    }

    fun parseArgs(args: List<String>): Int {
        return try {
            parse(args)
            0
        } catch (e: PrintHelpMessage) {
            println(e.context?.command?.getFormattedHelp() ?: getFormattedHelp())
            0
        } catch (e: CliktError) {
            val isJson = args.contains("--json") || json
            if (isJson) {
                println(JsonRenderer.renderError(e.message ?: "CLI error"))
            } else {
                System.err.println(e.message)
            }
            1
        } catch (e: KanbanException) {
            val isJson = args.contains("--json") || json
            if (isJson) {
                println(JsonRenderer.renderError(e.message ?: "Kanban error"))
            } else {
                System.err.println("Error: ${e.message}")
            }
            1
        } catch (e: Exception) {
            val isJson = args.contains("--json") || json
            if (isJson) {
                println(JsonRenderer.renderError(e.message ?: "Internal error"))
            } else {
                System.err.println("Error: ${e.message}")
            }
            1
        }
    }
}
