package aikanban.cli

import aikanban.cli.prompt.InteractivePrompter
import aikanban.cli.prompt.TerminalInteractivePrompter
import aikanban.cli.setup.CliSetupWizard
import aikanban.config.AiKanbanConfig
import aikanban.config.AiKanbanConfigLoader
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
    val terminal: Terminal = Terminal(),
    val jsonOutput: Boolean = false,
) {
    fun resolveOperator(
        explicit: String?,
        fallback: String = aikanban.cli.context.OperatorResolver.DEFAULT_FALLBACK,
    ): String {
        return aikanban.cli.context.OperatorResolver.resolve(
            explicitOperator = explicit,
            config = config,
            gitCommandRunner = gitCommandRunner,
            workingDir = workingDir,
            fallback = fallback,
        )
    }

    fun ensureConfig(
        overrideProvider: String? = null,
        isJson: Boolean = false,
        bypassIfTargetSpecified: Boolean = false,
    ): AiKanbanConfig {
        return CliSetupWizard.ensureConfig(
            cliContext = this,
            overrideProvider = overrideProvider,
            isJson = isJson,
            bypassIfTargetSpecified = bypassIfTargetSpecified,
        )
    }
}
