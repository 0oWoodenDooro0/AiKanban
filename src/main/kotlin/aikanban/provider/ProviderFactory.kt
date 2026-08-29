package aikanban.provider

import aikanban.config.AiKanbanConfig
import aikanban.github.service.DefaultGitHubSyncService
import aikanban.github.service.GitHubSyncService
import aikanban.service.KanbanService
import java.io.File

class ProviderFactory(
    private val kanbanService: KanbanService,
    private val gitHubSyncService: GitHubSyncService = DefaultGitHubSyncService(kanbanService),
    private val gitCommandRunner: GitCommandRunner = DefaultGitCommandRunner(),
    private val workingDir: File = File("."),
) {
    fun resolve(
        overrideProvider: String? = null,
        config: AiKanbanConfig = AiKanbanConfig(),
    ): IssueTrackerProvider {
        val providerName =
            overrideProvider?.trim()?.takeIf { it.isNotBlank() }
                ?: config.provider.trim().takeIf { it.isNotBlank() }
                ?: System.getenv("AIKANBAN_PROVIDER")?.trim()?.takeIf { it.isNotBlank() }
                ?: "local-git"

        return when (providerName.lowercase()) {
            "local-git", "local", "git" -> {
                LocalGitProvider(
                    gitCommandRunner = gitCommandRunner,
                    workingDir = workingDir,
                )
            }
            "github", "gh" -> {
                GitHubProvider(
                    kanbanService = kanbanService,
                    gitHubSyncService = gitHubSyncService,
                    gitCommandRunner = gitCommandRunner,
                    workingDir = workingDir,
                    defaultRepo = config.repo,
                    token = config.token,
                )
            }
            else -> {
                throw IllegalArgumentException(
                    "Unsupported provider: '$providerName'. Supported providers are: 'local-git', 'github'.",
                )
            }
        }
    }
}
