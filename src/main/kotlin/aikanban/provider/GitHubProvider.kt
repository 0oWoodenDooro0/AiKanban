package aikanban.provider

import aikanban.github.client.GitHubClient
import aikanban.github.client.KtorGitHubClient
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
) : IssueTrackerProvider {
    override val name: String = "github"

    private fun runGh(args: List<String>): GitProcessResult {
        return ghCliRunner.runGh(args, workingDir, token)
    }

    override fun resolveResource(url: String): ResolvedResource? {
        return GitHubUrlParser.parse(url)
    }

    private fun extractIssueUrl(stdout: String): ResolvedResource? {
        for (line in stdout.lines()) {
            val trimmed = line.trim()
            val parsed = GitHubUrlParser.parseIssue(trimmed)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun extractPullRequestUrl(stdout: String): ResolvedResource? {
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
        val parsed = GitHubUrlParser.parse(target)

        return if (parsed?.type == ResourceType.ISSUE || parsed?.type == ResourceType.PULL_REQUEST) {
            val owner = parsed.owner ?: throw IllegalArgumentException("Owner missing in resource: $target")
            val repo = parsed.repo ?: throw IllegalArgumentException("Repo missing in resource: $target")
            val issueNumber = parsed.number ?: 0
            val fullRepo = "$owner/$repo"

            val issue =
                gitHubClient.fetchIssue(
                    owner = owner,
                    repo = repo,
                    number = issueNumber,
                    token = effectiveToken,
                ) ?: throw IllegalArgumentException("${parsed.type} #$issueNumber not found in $fullRepo")

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
                repo = fullRepo,
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

            val owner = parsedRepo.owner ?: throw IllegalArgumentException("Owner missing in repository: $target")
            val repo = parsedRepo.repo ?: throw IllegalArgumentException("Repo missing in repository: $target")
            val fullRepo = "$owner/$repo"

            val issues =
                gitHubClient.fetchRepositoryIssues(
                    owner = owner,
                    repo = repo,
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
                repo = fullRepo,
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
