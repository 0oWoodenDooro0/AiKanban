package aikanban.provider

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class LocalGitProvider(
    private val gitCommandRunner: GitCommandRunner = DefaultGitCommandRunner(),
    private val workingDir: File = File("."),
) : IssueTrackerProvider {
    override val name: String = "local-git"

    private val localIssueCounter = AtomicInteger(1)

    override fun resolveResource(url: String): ResolvedResource? {
        if (url.startsWith("local://issue/")) {
            val id = url.removePrefix("local://issue/")
            val number = id.removePrefix("LOCAL-").toIntOrNull()
            return ResolvedResource(
                provider = name,
                owner = null,
                repo = "local",
                type = ResourceType.ISSUE,
                number = number,
                canonicalUrl = url,
            )
        } else if (url.startsWith("local://pull/")) {
            return ResolvedResource(
                provider = name,
                owner = null,
                repo = "local",
                type = ResourceType.PULL_REQUEST,
                number = null,
                canonicalUrl = url,
            )
        }
        return null
    }

    override suspend fun createIssue(request: CreateIssueRequest): IssueResult {
        val issueNumber = localIssueCounter.getAndIncrement()
        val localId = "LOCAL-$issueNumber"
        return IssueResult(
            id = localId,
            number = issueNumber,
            title = request.title,
            url = "local://issue/$localId",
            body = request.body,
        )
    }

    override suspend fun updateIssue(request: UpdateIssueRequest): IssueResult {
        return IssueResult(
            id = request.issueIdOrUrl,
            title = request.title ?: "",
            url = request.issueIdOrUrl,
            body = request.body,
        )
    }

    override suspend fun approvePullRequest(request: ApprovePullRequestRequest): Boolean = true

    override suspend fun requestChangesPullRequest(request: RequestChangesPullRequestRequest): Boolean = true

    override suspend fun mergePullRequest(request: MergePullRequestRequest): Boolean {
        if (request.prNumberOrUrl.startsWith("local://pull/")) {
            val branch = request.prNumberOrUrl.removePrefix("local://pull/")
            gitCommandRunner.mergeBranch(branch, squash = request.mergeMethod.equals("squash", ignoreCase = true), workingDir = workingDir)
            if (request.deleteBranch) {
                gitCommandRunner.deleteBranch(branch, force = true, workingDir = workingDir)
            }
        }
        return true
    }

    override suspend fun addComment(request: AddIssueCommentRequest): Boolean {
        return true
    }

    override suspend fun createBranch(request: CreateBranchRequest): BranchResult {
        val result = gitCommandRunner.createBranchOnly(request.branchName, request.baseBranch, workingDir)
        return BranchResult(
            branchName = request.branchName,
            baseBranch = request.baseBranch,
            created = result.exitCode == 0,
            linkedIssueUrl = request.issueIdOrUrl,
            message = if (result.exitCode == 0) result.stdout else result.stderr,
        )
    }

    override suspend fun createPullRequest(request: CreatePullRequestRequest): PullRequestResult {
        gitCommandRunner.pushBranch(request.headBranch, "origin", true, workingDir)
        val localPrUrl = "local://pull/${request.headBranch}"
        return PullRequestResult(
            url = localPrUrl,
            title = request.title,
            headBranch = request.headBranch,
            baseBranch = request.baseBranch,
            draft = request.draft,
        )
    }

    override suspend fun sync(request: ProviderSyncRequest): ProviderSyncResult {
        return ProviderSyncResult(
            provider = name,
            repo = request.repoOrUrl ?: "local",
            totalFetched = 0,
            createdCount = 0,
            updatedCount = 0,
            skippedCount = 0,
            tasks = emptyList(),
        )
    }
}
