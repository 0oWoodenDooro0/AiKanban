package aikanban.provider

import aikanban.github.client.GitHubClient
import aikanban.github.client.KtorGitHubClient
import aikanban.github.model.GitHubResource
import aikanban.github.service.GitHubSyncService
import aikanban.github.service.GitHubUrlParser
import aikanban.provider.ingestion.DefaultIssueIngestionPipeline
import aikanban.provider.ingestion.IssueIngestionPipeline
import aikanban.provider.ingestion.RawIssueData
import aikanban.service.KanbanService
import aikanban.service.exception.KanbanException
import java.io.File

class GitHubProvider(
    private val kanbanService: KanbanService,
    private val gitHubClient: GitHubClient = KtorGitHubClient(),
    private val gitCommandRunner: GitCommandRunner = DefaultGitCommandRunner(),
    private val ghCliRunner: GhCliRunner = DefaultGhCliRunner(),
    private val workingDir: File = File("."),
    private val defaultRepo: String? = null,
    private val token: String? = null,
    private val ingestionPipeline: IssueIngestionPipeline = DefaultIssueIngestionPipeline(kanbanService),
    private val gitHubSyncService: GitHubSyncService? = null,
) : IssueTrackerProvider {
    override val name: String = "github"

    private fun runGh(args: List<String>): GitProcessResult {
        return ghCliRunner.runGh(args, workingDir, token)
    }

    override fun resolveResource(url: String): ResolvedResource? {
        val resource = GitHubUrlParser.parse(url) ?: return null
        val number =
            when (resource) {
                is GitHubResource.Issue -> resource.number
                is GitHubResource.PullRequest -> resource.number
                is GitHubResource.Repository -> null
            }
        val resType =
            when (resource) {
                is GitHubResource.Issue -> ResourceType.ISSUE
                is GitHubResource.PullRequest -> ResourceType.PULL_REQUEST
                is GitHubResource.Repository -> ResourceType.REPOSITORY
            }
        return ResolvedResource(
            provider = name,
            owner = resource.owner,
            repo = resource.repo,
            type = resType,
            number = number,
            canonicalUrl = resource.canonicalUrl,
        )
    }

    private fun extractIssueUrl(stdout: String): GitHubResource.Issue? {
        for (line in stdout.lines()) {
            val trimmed = line.trim()
            val parsed = GitHubUrlParser.parseIssue(trimmed)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun extractPullRequestUrl(stdout: String): GitHubResource.PullRequest? {
        for (line in stdout.lines()) {
            val trimmed = line.trim()
            val parsed = GitHubUrlParser.parsePullRequest(trimmed)
            if (parsed != null) return parsed
        }
        return null
    }

    override suspend fun createIssue(request: CreateIssueRequest): IssueResult {
        fun buildArgs(
            includeLabels: Boolean,
            includeAssignee: Boolean,
        ): List<String> {
            val ghArgs = mutableListOf("issue", "create", "--title", request.title, "--body", request.body)
            if (includeLabels && request.labels.isNotEmpty()) {
                ghArgs.add("--label")
                ghArgs.add(request.labels.joinToString(","))
            }
            if (includeAssignee && !request.assignee.isNullOrBlank()) {
                ghArgs.add("--assignee")
                ghArgs.add(request.assignee)
            }
            if (!defaultRepo.isNullOrBlank()) {
                ghArgs.add("--repo")
                ghArgs.add(defaultRepo)
            }
            return ghArgs
        }

        // Attempt 1: Full options (with labels and assignee)
        var res = runGh(buildArgs(includeLabels = true, includeAssignee = true))
        var issueResource = if (res.exitCode == 0) extractIssueUrl(res.stdout) else null

        // If failed due to labels, attempt 2 without labels
        if (issueResource == null && request.labels.isNotEmpty()) {
            val retryRes = runGh(buildArgs(includeLabels = false, includeAssignee = true))
            if (retryRes.exitCode == 0) {
                val parsed = extractIssueUrl(retryRes.stdout)
                if (parsed != null) {
                    res = retryRes
                    issueResource = parsed
                }
            }
        }

        // If failed due to assignee, attempt 3 without assignee
        if (issueResource == null && !request.assignee.isNullOrBlank()) {
            val retryRes = runGh(buildArgs(includeLabels = request.labels.isNotEmpty(), includeAssignee = false))
            if (retryRes.exitCode == 0) {
                val parsed = extractIssueUrl(retryRes.stdout)
                if (parsed != null) {
                    res = retryRes
                    issueResource = parsed
                }
            }
        }

        // If failed due to both labels and assignee, attempt 4 without both
        if (issueResource == null && request.labels.isNotEmpty() && !request.assignee.isNullOrBlank()) {
            val retryRes = runGh(buildArgs(includeLabels = false, includeAssignee = false))
            if (retryRes.exitCode == 0) {
                val parsed = extractIssueUrl(retryRes.stdout)
                if (parsed != null) {
                    res = retryRes
                    issueResource = parsed
                }
            }
        }

        if (issueResource == null) {
            val errorMsg = res.stderr.ifBlank { res.stdout }.ifBlank { "Unknown gh issue create error (exit code ${res.exitCode})" }
            throw KanbanException("Failed to create GitHub issue: $errorMsg")
        }

        return IssueResult(
            id = issueResource.number.toString(),
            number = issueResource.number,
            title = request.title,
            url = issueResource.canonicalUrl,
            body = request.body,
        )
    }

    override suspend fun addComment(request: AddIssueCommentRequest): Boolean {
        val ghArgs = mutableListOf("issue", "comment", request.issueIdOrUrl, "--body", request.comment)
        if (!defaultRepo.isNullOrBlank() && !request.issueIdOrUrl.startsWith("http")) {
            ghArgs.add("--repo")
            ghArgs.add(defaultRepo)
        }
        val res = runGh(ghArgs)
        return res.exitCode == 0
    }

    override suspend fun createBranch(request: CreateBranchRequest): BranchResult {
        // Try linking with gh issue develop if issue URL / ID is provided
        if (!request.issueIdOrUrl.isNullOrBlank()) {
            val ghDevelopArgs =
                mutableListOf(
                    "issue",
                    "develop",
                    request.issueIdOrUrl,
                    "--name",
                    request.branchName,
                    "--base",
                    request.baseBranch,
                )
            if (!defaultRepo.isNullOrBlank() && !request.issueIdOrUrl.startsWith("http")) {
                ghDevelopArgs.add("--repo")
                ghDevelopArgs.add(defaultRepo)
            }
            val ghDevelopRes = runGh(ghDevelopArgs)
            if (ghDevelopRes.exitCode == 0) {
                return BranchResult(
                    branchName = request.branchName,
                    baseBranch = request.baseBranch,
                    created = true,
                    linkedIssueUrl = request.issueIdOrUrl,
                    message = ghDevelopRes.stdout,
                )
            }
        }

        val gitRes = gitCommandRunner.createBranchOnly(request.branchName, request.baseBranch, workingDir)
        return BranchResult(
            branchName = request.branchName,
            baseBranch = request.baseBranch,
            created = gitRes.exitCode == 0,
            linkedIssueUrl = request.issueIdOrUrl,
            message = if (gitRes.exitCode == 0) gitRes.stdout else gitRes.stderr,
        )
    }

    override suspend fun createPullRequest(request: CreatePullRequestRequest): PullRequestResult {
        // Push branch first
        gitCommandRunner.pushBranch(request.headBranch, "origin", true, workingDir)

        val ghArgs =
            mutableListOf(
                "pr",
                "create",
                "--title",
                request.title,
                "--body",
                request.body,
                "--head",
                request.headBranch,
                "--base",
                request.baseBranch,
            )
        if (request.draft) {
            ghArgs.add("--draft")
        }
        if (!defaultRepo.isNullOrBlank()) {
            ghArgs.add("--repo")
            ghArgs.add(defaultRepo)
        }

        val res = runGh(ghArgs)
        val prResource = if (res.exitCode == 0) extractPullRequestUrl(res.stdout) else null

        if (prResource == null) {
            val errorMsg = res.stderr.ifBlank { res.stdout }.ifBlank { "Unknown gh pr create error (exit code ${res.exitCode})" }
            throw KanbanException("Failed to create GitHub pull request: $errorMsg")
        }

        return PullRequestResult(
            url = prResource.canonicalUrl,
            number = prResource.number,
            title = request.title,
            headBranch = request.headBranch,
            baseBranch = request.baseBranch,
            draft = request.draft,
        )
    }

    override suspend fun updateIssue(request: UpdateIssueRequest): IssueResult {
        val ghArgs = mutableListOf("issue", "edit", request.issueIdOrUrl)
        if (!request.title.isNullOrBlank()) {
            ghArgs.addAll(listOf("--title", request.title))
        }
        if (request.body != null) {
            ghArgs.addAll(listOf("--body", request.body))
        }
        if (!request.assignee.isNullOrBlank()) {
            ghArgs.addAll(listOf("--add-assignee", request.assignee))
        }
        if (!request.labels.isNullOrEmpty()) {
            ghArgs.addAll(listOf("--add-label", request.labels.joinToString(",")))
        }
        if (!defaultRepo.isNullOrBlank() && !request.issueIdOrUrl.startsWith("http")) {
            ghArgs.addAll(listOf("--repo", defaultRepo))
        }

        val res = runGh(ghArgs)
        if (res.exitCode != 0) {
            val errorMsg = res.stderr.ifBlank { res.stdout }.ifBlank { "Unknown gh issue edit error (exit code ${res.exitCode})" }
            throw KanbanException("Failed to update GitHub issue: $errorMsg")
        }

        return IssueResult(
            id = request.issueIdOrUrl,
            title = request.title ?: "",
            url = request.issueIdOrUrl,
            body = request.body,
        )
    }

    override suspend fun approvePullRequest(request: ApprovePullRequestRequest): Boolean {
        val ghArgs = mutableListOf("pr", "review", request.prNumberOrUrl, "--approve")
        if (!request.comment.isNullOrBlank()) {
            ghArgs.addAll(listOf("--body", request.comment))
        }
        if (!defaultRepo.isNullOrBlank() && !request.prNumberOrUrl.startsWith("http")) {
            ghArgs.addAll(listOf("--repo", defaultRepo))
        }
        val res = runGh(ghArgs)
        return res.exitCode == 0
    }

    override suspend fun requestChangesPullRequest(request: RequestChangesPullRequestRequest): Boolean {
        val commentBody = request.comment.ifBlank { "Changes requested" }
        val ghArgs = mutableListOf("pr", "review", request.prNumberOrUrl, "--request-changes", "--body", commentBody)
        if (!defaultRepo.isNullOrBlank() && !request.prNumberOrUrl.startsWith("http")) {
            ghArgs.addAll(listOf("--repo", defaultRepo))
        }
        val res = runGh(ghArgs)
        return res.exitCode == 0
    }

    override suspend fun mergePullRequest(request: MergePullRequestRequest): Boolean {
        val ghArgs = mutableListOf("pr", "merge", request.prNumberOrUrl)
        when (request.mergeMethod.lowercase()) {
            "merge" -> ghArgs.add("--merge")
            "rebase" -> ghArgs.add("--rebase")
            else -> ghArgs.add("--squash")
        }
        if (request.deleteBranch) {
            ghArgs.add("--delete-branch")
        }
        if (!request.commitMessage.isNullOrBlank()) {
            ghArgs.addAll(listOf("--subject", request.commitMessage))
        }
        if (!defaultRepo.isNullOrBlank() && !request.prNumberOrUrl.startsWith("http")) {
            ghArgs.addAll(listOf("--repo", defaultRepo))
        }
        val res = runGh(ghArgs)
        return res.exitCode == 0
    }

    override suspend fun sync(request: ProviderSyncRequest): ProviderSyncResult {
        val target = request.repoOrUrl ?: defaultRepo
        if (target.isNullOrBlank()) {
            throw IllegalArgumentException("Please specify a GitHub repository ('owner/repo') or an issue URL.")
        }

        val effectiveToken = request.token ?: token

        if (gitHubSyncService != null) {
            val parsed = GitHubUrlParser.parse(target)
            val syncRes =
                if (parsed is GitHubResource.Issue || parsed is GitHubResource.PullRequest) {
                    gitHubSyncService.syncUrl(
                        url = target,
                        targetStatus = request.targetStatus,
                        token = effectiveToken,
                        operator = request.operator,
                        dryRun = request.dryRun,
                    )
                } else {
                    gitHubSyncService.syncRepository(
                        repo = target,
                        state = request.state,
                        labels = request.labels,
                        includePullRequests = request.includePullRequests,
                        targetStatus = request.targetStatus,
                        token = effectiveToken,
                        operator = request.operator,
                        dryRun = request.dryRun,
                    )
                }
            return ProviderSyncResult(
                provider = name,
                repo = syncRes.repo,
                totalFetched = syncRes.totalFetched,
                createdCount = syncRes.createdCount,
                updatedCount = syncRes.updatedCount,
                skippedCount = syncRes.skippedCount,
                tasks = syncRes.tasks,
            )
        }

        val parsed = GitHubUrlParser.parse(target)

        return if (parsed is GitHubResource.Issue || parsed is GitHubResource.PullRequest) {
            val issueNumber =
                when (parsed) {
                    is GitHubResource.Issue -> parsed.number
                    is GitHubResource.PullRequest -> parsed.number
                    else -> 0
                }

            val issue =
                gitHubClient.fetchIssue(
                    owner = parsed.owner,
                    repo = parsed.repo,
                    number = issueNumber,
                    token = effectiveToken,
                ) ?: throw IllegalArgumentException("${parsed.type} #$issueNumber not found in ${parsed.fullRepo}")

            val rawIssue =
                RawIssueData(
                    id = issue.id.toString(),
                    number = issue.number,
                    title = issue.title,
                    body = issue.body,
                    state = issue.state,
                    htmlUrl = issue.htmlUrl,
                    assignee = issue.assignee?.login,
                    labels = issue.labels.map { it.name },
                    isPullRequest = issue.pullRequest != null,
                    prUrl = issue.pullRequest?.htmlUrl,
                )

            ingestionPipeline.ingest(
                issues = listOf(rawIssue),
                repo = parsed.fullRepo,
                targetStatus = request.targetStatus,
                operator = request.operator,
                dryRun = request.dryRun,
                providerName = name,
                totalFetched = 1,
                skippedCount = 0,
            )
        } else {
            val parsedRepo =
                GitHubUrlParser.parseRepository(target)
                    ?: throw IllegalArgumentException("Invalid repository format: $target. Expected 'owner/repo' or GitHub repository URL.")

            val issues =
                gitHubClient.fetchRepositoryIssues(
                    owner = parsedRepo.owner,
                    repo = parsedRepo.repo,
                    state = request.state,
                    labels = request.labels,
                    token = effectiveToken,
                )

            val filteredIssues =
                if (request.includePullRequests) {
                    issues
                } else {
                    issues.filter { it.pullRequest == null }
                }

            val rawIssues =
                filteredIssues.map { issue ->
                    RawIssueData(
                        id = issue.id.toString(),
                        number = issue.number,
                        title = issue.title,
                        body = issue.body,
                        state = issue.state,
                        htmlUrl = issue.htmlUrl,
                        assignee = issue.assignee?.login,
                        labels = issue.labels.map { it.name },
                        isPullRequest = issue.pullRequest != null,
                        prUrl = issue.pullRequest?.htmlUrl,
                    )
                }

            val skipped = issues.size - filteredIssues.size

            ingestionPipeline.ingest(
                issues = rawIssues,
                repo = parsedRepo.fullRepo,
                targetStatus = request.targetStatus,
                operator = request.operator,
                dryRun = request.dryRun,
                providerName = name,
                totalFetched = filteredIssues.size,
                skippedCount = skipped,
            )
        }
    }
}
