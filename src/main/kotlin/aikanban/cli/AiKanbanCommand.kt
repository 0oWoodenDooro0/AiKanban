package aikanban.cli

import aikanban.cli.command.AddCommand
import aikanban.cli.command.ClaimCommand
import aikanban.cli.command.ColumnCommand
import aikanban.cli.command.ListCommand
import aikanban.cli.command.LogCommand
import aikanban.cli.command.MoveCommand
import aikanban.cli.command.ServeCommand
import aikanban.cli.command.ShowCommand
import aikanban.cli.command.SyncCommand
import aikanban.cli.command.SyncGitHubCommand
import aikanban.cli.command.UpdateCommand
import aikanban.cli.command.WorkflowCommand
import aikanban.cli.renderer.JsonRenderer
import aikanban.config.AiKanbanConfig
import aikanban.config.AiKanbanConfigLoader
import aikanban.github.service.DefaultGitHubSyncService
import aikanban.github.service.GitHubSyncService
import aikanban.provider.DefaultGitCommandRunner
import aikanban.provider.ProviderFactory
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import aikanban.service.exception.KanbanException
import aikanban.workflow.DefaultKanbanWorkflowService
import aikanban.workflow.KanbanWorkflowService
import com.github.ajalt.clikt.completion.completionOption
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintCompletionMessage
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal

class AiKanbanCommand(
    private val serviceOverride: KanbanService? = null,
    private val gitHubSyncServiceOverride: GitHubSyncService? = null,
    private val configOverride: AiKanbanConfig? = null,
    private val providerFactoryOverride: ProviderFactory? = null,
    private val workflowServiceOverride: KanbanWorkflowService? = null,
    private val terminal: Terminal = Terminal(),
) : CliktCommand(name = "aikanban") {
    override fun help(context: Context): String = "AiKanban - Universal CLI Kanban Board for Humans and AI Agents"

    val db by option("--db", help = "Path to SQLite database file", envvar = "AIKANBAN_DB")
        .default("aikanban.db")
    val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    init {
        completionOption(help = "Generate shell completion script (bash, zsh, fish)")
        subcommands(
            ListCommand(),
            AddCommand(),
            ShowCommand(),
            MoveCommand(),
            ClaimCommand(),
            LogCommand(),
            UpdateCommand(),
            ColumnCommand(),
            SyncCommand(),
            WorkflowCommand(),
            SyncGitHubCommand(),
            ServeCommand(),
        )
    }

    override fun run() {
        val service = serviceOverride ?: DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:$db"))
        val config = configOverride ?: AiKanbanConfigLoader.load()
        val gitRunner = DefaultGitCommandRunner()
        val providerFactory = providerFactoryOverride ?: ProviderFactory(service, gitCommandRunner = gitRunner)
        val workflowService =
            workflowServiceOverride
                ?: DefaultKanbanWorkflowService(
                    kanbanService = service,
                    providerFactory = providerFactory,
                    config = config,
                    gitCommandRunner = gitRunner,
                )
        val syncService = gitHubSyncServiceOverride ?: DefaultGitHubSyncService(service)

        currentContext.findOrSetObject {
            CliContext(
                service = service,
                config = config,
                gitCommandRunner = gitRunner,
                providerFactory = providerFactory,
                workflowService = workflowService,
                gitHubSyncService = syncService,
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
        } catch (e: PrintCompletionMessage) {
            println(e.message)
            0
        } catch (e: PrintMessage) {
            println(e.message)
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
