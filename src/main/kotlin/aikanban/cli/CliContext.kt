package aikanban.cli

import aikanban.github.service.DefaultGitHubSyncService
import aikanban.github.service.GitHubSyncService
import aikanban.service.KanbanService
import com.github.ajalt.mordant.terminal.Terminal

data class CliContext(
    val service: KanbanService,
    val gitHubSyncService: GitHubSyncService = DefaultGitHubSyncService(service),
    val terminal: Terminal = Terminal(),
    val jsonOutput: Boolean = false,
)
