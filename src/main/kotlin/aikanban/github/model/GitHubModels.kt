package aikanban.github.model

import aikanban.model.Task
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubIssueDto(
    val id: Long = 0,
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String = "open",
    @SerialName("state_reason")
    val stateReason: String? = null,
    @SerialName("html_url")
    val htmlUrl: String,
    val user: GitHubUserDto? = null,
    val assignee: GitHubUserDto? = null,
    val assignees: List<GitHubUserDto> = emptyList(),
    val labels: List<GitHubLabelDto> = emptyList(),
    @SerialName("pull_request")
    val pullRequest: GitHubPullRequestRefDto? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("closed_at")
    val closedAt: String? = null,
)

@Serializable
data class GitHubUserDto(
    val id: Long? = null,
    val login: String,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
)

@Serializable
data class GitHubLabelDto(
    val id: Long? = null,
    val name: String,
    val color: String? = null,
    val description: String? = null,
)

@Serializable
data class GitHubPullRequestRefDto(
    val url: String? = null,
    @SerialName("html_url")
    val htmlUrl: String? = null,
)

@Serializable
data class GitHubSyncResult(
    val repo: String,
    val totalFetched: Int,
    val createdCount: Int,
    val updatedCount: Int,
    val skippedCount: Int = 0,
    val tasks: List<Task> = emptyList(),
    val errors: List<String> = emptyList(),
)
