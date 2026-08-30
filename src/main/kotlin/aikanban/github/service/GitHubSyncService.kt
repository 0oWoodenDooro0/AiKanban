package aikanban.github.service

import aikanban.github.client.GitHubClient
import aikanban.github.client.KtorGitHubClient
import aikanban.github.model.GitHubSyncResult
import aikanban.provider.GitHubProvider
import aikanban.provider.ProviderSyncRequest
import aikanban.service.KanbanService

interface GitHubSyncService {
    suspend fun syncRepository(
        repo: String,
        state: String = "open",
        labels: Set<String> = emptySet(),
        includePullRequests: Boolean = false,
        targetStatus: String = "TODO",
        token: String? = null,
        operator: String = "github-sync",
        dryRun: Boolean = false,
    ): GitHubSyncResult

    suspend fun syncUrl(
        url: String,
        targetStatus: String = "TODO",
        token: String? = null,
        operator: String = "github-sync",
        dryRun: Boolean = false,
    ): GitHubSyncResult
}

class DefaultGitHubSyncService(
    private val kanbanService: KanbanService,
    private val gitHubClient: GitHubClient = KtorGitHubClient(),
) : GitHubSyncService {
    private val provider =
        GitHubProvider(
            kanbanService = kanbanService,
            gitHubClient = gitHubClient,
        )

    override suspend fun syncRepository(
        repo: String,
        state: String,
        labels: Set<String>,
        includePullRequests: Boolean,
        targetStatus: String,
        token: String?,
        operator: String,
        dryRun: Boolean,
    ): GitHubSyncResult {
        val res =
            provider.sync(
                ProviderSyncRequest(
                    repoOrUrl = repo,
                    state = state,
                    labels = labels,
                    includePullRequests = includePullRequests,
                    targetStatus = targetStatus,
                    token = token,
                    operator = operator,
                    dryRun = dryRun,
                ),
            )

        return GitHubSyncResult(
            repo = res.repo ?: repo,
            totalFetched = res.totalFetched,
            createdCount = res.createdCount,
            updatedCount = res.updatedCount,
            skippedCount = res.skippedCount,
            tasks = res.tasks,
        )
    }

    override suspend fun syncUrl(
        url: String,
        targetStatus: String,
        token: String?,
        operator: String,
        dryRun: Boolean,
    ): GitHubSyncResult {
        val res =
            provider.sync(
                ProviderSyncRequest(
                    repoOrUrl = url,
                    targetStatus = targetStatus,
                    token = token,
                    operator = operator,
                    dryRun = dryRun,
                ),
            )

        return GitHubSyncResult(
            repo = res.repo ?: url,
            totalFetched = res.totalFetched,
            createdCount = res.createdCount,
            updatedCount = res.updatedCount,
            skippedCount = res.skippedCount,
            tasks = res.tasks,
        )
    }
}
