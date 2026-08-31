package aikanban.cli

import aikanban.cli.prompt.InteractivePrompter
import aikanban.cli.prompt.TerminalInteractivePrompter
import aikanban.config.AiKanbanConfig
import aikanban.config.AiKanbanConfigLoader
import aikanban.github.service.DefaultGitHubSyncService
import aikanban.github.service.GitHubSyncService
import aikanban.provider.DefaultGitCommandRunner
import aikanban.provider.GitCommandRunner
import aikanban.provider.ProviderFactory
import aikanban.service.KanbanService
import aikanban.workflow.DefaultKanbanWorkflowService
import aikanban.workflow.KanbanWorkflowService
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File

data class CliContext(
    val service: KanbanService,
    val config: AiKanbanConfig = AiKanbanConfigLoader.load(),
    val gitCommandRunner: GitCommandRunner = DefaultGitCommandRunner(),
    val workingDir: File = File("."),
    val prompter: InteractivePrompter = TerminalInteractivePrompter(Terminal()),
    val providerFactory: ProviderFactory =
        ProviderFactory(
            service,
            gitCommandRunner = gitCommandRunner,
            workingDir = workingDir,
        ),
    val workflowService: KanbanWorkflowService =
        DefaultKanbanWorkflowService(
            kanbanService = service,
            providerFactory = providerFactory,
            config = config,
            gitCommandRunner = gitCommandRunner,
        ),
    val gitHubSyncService: GitHubSyncService = DefaultGitHubSyncService(service),
    val terminal: Terminal = Terminal(),
    val jsonOutput: Boolean = false,
)
