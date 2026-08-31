package aikanban.provider

import aikanban.model.Task
import aikanban.model.TaskPriority
import kotlinx.serialization.Serializable

@Serializable
enum class ResourceType {
    ISSUE,
    PULL_REQUEST,
    REPOSITORY,
}

@Serializable
data class ResolvedResource(
    val provider: String,
    val owner: String? = null,
    val repo: String? = null,
    val type: ResourceType,
    val number: Int? = null,
    val canonicalUrl: String,
)

@Serializable
data class CreateIssueRequest(
    val title: String,
    val body: String = "",
    val labels: Set<String> = emptySet(),
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val assignee: String? = null,
)

@Serializable
data class IssueResult(
    val id: String,
    val number: Int? = null,
    val title: String,
    val url: String? = null,
    val body: String? = null,
)

@Serializable
data class AddIssueCommentRequest(
    val issueIdOrUrl: String,
    val comment: String,
)

@Serializable
data class CreateBranchRequest(
    val branchName: String,
    val baseBranch: String = "main",
    val issueIdOrUrl: String? = null,
)

@Serializable
data class BranchResult(
    val branchName: String,
    val baseBranch: String,
    val created: Boolean,
    val linkedIssueUrl: String? = null,
    val message: String? = null,
)

@Serializable
data class CreatePullRequestRequest(
    val title: String,
    val body: String = "",
    val headBranch: String,
    val baseBranch: String = "main",
    val draft: Boolean = false,
)

@Serializable
data class PullRequestResult(
    val url: String,
    val number: Int? = null,
    val title: String,
    val headBranch: String,
    val baseBranch: String,
    val draft: Boolean = false,
)

@Serializable
data class ProviderSyncRequest(
    val repoOrUrl: String? = null,
    val state: String = "open",
    val labels: Set<String> = emptySet(),
    val includePullRequests: Boolean = false,
    val targetStatus: String = "TODO",
    val token: String? = null,
    val operator: String = "sync",
    val dryRun: Boolean = false,
)

@Serializable
data class ProviderSyncResult(
    val provider: String,
    val repo: String? = null,
    val totalFetched: Int = 0,
    val createdCount: Int = 0,
    val updatedCount: Int = 0,
    val skippedCount: Int = 0,
    val tasks: List<Task> = emptyList(),
)

@Serializable
data class UpdateIssueRequest(
    val issueIdOrUrl: String,
    val title: String? = null,
    val body: String? = null,
    val state: String? = null,
    val labels: Set<String>? = null,
    val assignee: String? = null,
)

@Serializable
data class ApprovePullRequestRequest(
    val prNumberOrUrl: String,
    val comment: String? = null,
)

@Serializable
data class RequestChangesPullRequestRequest(
    val prNumberOrUrl: String,
    val comment: String,
)

@Serializable
data class MergePullRequestRequest(
    val prNumberOrUrl: String,
    val mergeMethod: String = "squash",
    val deleteBranch: Boolean = true,
    val commitMessage: String? = null,
)

interface IssueTrackerProvider {
    val name: String

    fun resolveResource(url: String): ResolvedResource?

    suspend fun createIssue(request: CreateIssueRequest): IssueResult

    suspend fun updateIssue(request: UpdateIssueRequest): IssueResult =
        IssueResult(id = request.issueIdOrUrl, title = request.title ?: "", url = request.issueIdOrUrl, body = request.body)

    suspend fun addComment(request: AddIssueCommentRequest): Boolean

    suspend fun createBranch(request: CreateBranchRequest): BranchResult

    suspend fun createPullRequest(request: CreatePullRequestRequest): PullRequestResult

    suspend fun approvePullRequest(request: ApprovePullRequestRequest): Boolean = true

    suspend fun approvePullRequest(
        prNumberOrUrl: String,
        comment: String? = null,
    ): Boolean = approvePullRequest(ApprovePullRequestRequest(prNumberOrUrl = prNumberOrUrl, comment = comment))

    suspend fun requestChangesPullRequest(request: RequestChangesPullRequestRequest): Boolean = true

    suspend fun requestChangesPullRequest(
        prNumberOrUrl: String,
        comment: String,
    ): Boolean = requestChangesPullRequest(RequestChangesPullRequestRequest(prNumberOrUrl = prNumberOrUrl, comment = comment))

    suspend fun mergePullRequest(request: MergePullRequestRequest): Boolean = true

    suspend fun sync(request: ProviderSyncRequest): ProviderSyncResult
}
