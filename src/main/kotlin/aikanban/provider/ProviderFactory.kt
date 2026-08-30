package aikanban.provider

import aikanban.config.AiKanbanConfig
import aikanban.github.client.GitHubClient
import aikanban.github.client.KtorGitHubClient
import aikanban.github.service.GitHubSyncService
import aikanban.service.KanbanService
import java.io.File

class ProviderFactory(
    private val kanbanService: KanbanService,
    private val gitHubClient: GitHubClient = KtorGitHubClient(),
    private val gitCommandRunner: GitCommandRunner = DefaultGitCommandRunner(),
    private val workingDir: File = File("."),
    private val gitHubSyncService: GitHubSyncService? = null,
) {
    fun resolve(
        overrideProvider: String? = null,
        config: AiKanbanConfig = AiKanbanConfig(),
        targetUrlOrRepo: String? = null,
    ): IssueTrackerProvider {
        val isGitHubTarget =
            targetUrlOrRepo != null &&
                (
                    targetUrlOrRepo.contains("github.com") ||
                        (targetUrlOrRepo.count { it == '/' } == 1 && !targetUrlOrRepo.startsWith("local"))
                )
        val detectedProvider = if (isGitHubTarget) "github" else null

        val providerName =
            overrideProvider?.trim()?.takeIf { it.isNotBlank() }
                ?: detectedProvider
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
                    gitHubClient = gitHubClient,
                    gitCommandRunner = gitCommandRunner,
                    workingDir = workingDir,
                    defaultRepo = config.repo,
                    token = config.token,
                    gitHubSyncService = gitHubSyncService,
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
