package aikanban.api.dto

import aikanban.model.TaskPriority
import kotlinx.serialization.Serializable

@Serializable
data class CreateTaskRequest(
    val title: String,
    val description: String = "",
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val assignee: String? = null,
    val tags: Set<String> = emptySet(),
    val githubRepo: String? = null,
    val githubIssueUrl: String? = null,
    val status: String = "TODO",
    val operator: String = "api",
)

@Serializable
data class UpdateTaskRequest(
    val title: String? = null,
    val description: String? = null,
    val priority: TaskPriority? = null,
    val assignee: String? = null,
    val tags: Set<String>? = null,
    val githubRepo: String? = null,
    val githubIssueUrl: String? = null,
    val githubPrUrl: String? = null,
    val operator: String = "api",
    val comment: String? = null,
)

@Serializable
data class MoveTaskRequest(
    val toStatus: String,
    val operator: String = "api",
    val comment: String? = null,
    val prUrl: String? = null,
    val assignee: String? = null,
)

@Serializable
data class ClaimTaskRequest(
    val agentName: String,
    val fromStatus: String = "TODO",
    val toStatus: String = "IN_PROGRESS",
    val tag: String? = null,
)

@Serializable
data class ReleaseTaskRequest(
    val operator: String = "api",
    val targetStatus: String = "TODO",
    val comment: String? = null,
)

@Serializable
data class AddCommentRequest(
    val operator: String = "api",
    val comment: String,
    val prUrl: String? = null,
    val commitHash: String? = null,
)

@Serializable
data class CreateColumnRequest(
    val id: String,
    val name: String,
    val order: Int = 0,
    val color: String = "#6B7280",
    val isTerminal: Boolean = false,
)

@Serializable
data class UpdateColumnRequest(
    val name: String,
    val order: Int = 0,
    val color: String = "#6B7280",
    val isTerminal: Boolean = false,
)

@Serializable
data class ErrorResponse(
    val error: String,
    val status: Int,
)

@Serializable
data class MessageResponse(
    val message: String,
)

@Serializable
data class GitHubSyncRequest(
    val repo: String? = null,
    val url: String? = null,
    val state: String = "open",
    val tags: Set<String> = emptySet(),
    val includePrs: Boolean = false,
    val targetStatus: String = "TODO",
    val token: String? = null,
    val operator: String = "api-github-sync",
    val dryRun: Boolean = false,
)

@Serializable
data class GitHubSyncResponse(
    val repo: String,
    val totalFetched: Int,
    val createdCount: Int,
    val updatedCount: Int,
    val skippedCount: Int = 0,
    val tasks: List<aikanban.model.Task> = emptyList(),
    val errors: List<String> = emptyList(),
)

@Serializable
data class GitHubResolveRequest(
    val url: String,
)

@Serializable
data class GitHubResolveResponse(
    val owner: String,
    val repo: String,
    val type: String,
    val number: Int? = null,
    val canonicalUrl: String,
)
