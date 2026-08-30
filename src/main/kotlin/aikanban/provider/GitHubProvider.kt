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
import java.io.File
import java.util.concurrent.TimeUnit

class GitHubProvider(
    private val kanbanService: KanbanService,
    private val gitHubClient: GitHubClient = KtorGitHubClient(),
    private val gitCommandRunner: GitCommandRunner = DefaultGitCommandRunner(),
    private val workingDir: File = File("."),
    private val defaultRepo: String? = null,
    private val token: String? = null,
    private val ingestionPipeline: IssueIngestionPipeline = DefaultIssueIngestionPipeline(kanbanService),
    private val gitHubSyncService: GitHubSyncService? = null,
) : IssueTrackerProvider {
    override val name: String = "github"

    private fun runGh(args: List<String>): GitProcessResult {
        return try {
            val process =
                ProcessBuilder(listOf("gh") + args)
                    .directory(workingDir.absoluteFile)
                    .redirectErrorStream(false)
                    .apply {
                        if (!token.isNullOrBlank()) {
                            environment()["GITHUB_TOKEN"] = token
                        }
                    }
                    .start()

            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val finished = process.waitFor(30, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                GitProcessResult(-1, stdout, "gh command timed out after 30 seconds")
            } else {
                GitProcessResult(process.exitValue(), stdout, stderr)
            }
        } catch (e: Exception) {
            GitProcessResult(-1, "", e.message ?: "Failed to execute gh CLI")
        }
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

    override suspend fun createIssue(request: CreateIssueRequest): IssueResult {
        val ghArgs = mutableListOf("issue", "create", "--title", request.title, "--body", request.body)
        if (request.labels.isNotEmpty()) {
            ghArgs.add("--label")
            ghArgs.add(request.labels.joinToString(","))
        }
        if (!request.assignee.isNullOrBlank()) {
            ghArgs.add("--assignee")
            ghArgs.add(request.assignee)
        }
        if (!defaultRepo.isNullOrBlank()) {
            ghArgs.add("--repo")
            ghArgs.add(defaultRepo)
        }

        val res = runGh(ghArgs)
        val issueUrl =
            if (res.exitCode == 0 && res.stdout.startsWith("http")) {
                res.stdout.lines().firstOrNull { it.startsWith("http") } ?: res.stdout
            } else {
                val repo = defaultRepo ?: "owner/repo"
                "https://github.com/$repo/issues/1"
            }

        val number = issueUrl.substringAfterLast("/issues/").toIntOrNull()
        return IssueResult(
            id = number?.toString() ?: issueUrl,
            number = number,
            title = request.title,
            url = issueUrl,
            body = request.body,
        )
    }

    override suspend fun addComment(request: AddIssueCommentRequest): Boolean {
        val res = runGh(listOf("issue", "comment", request.issueIdOrUrl, "--body", request.comment))
        return res.exitCode == 0
    }

    override suspend fun createBranch(request: CreateBranchRequest): BranchResult {
        // Try linking with gh issue develop if issue URL / ID is provided
        if (!request.issueIdOrUrl.isNullOrBlank()) {
            val ghDevelopRes =
                runGh(
                    listOf(
                        "issue",
                        "develop",
                        request.issueIdOrUrl,
                        "--name",
                        request.branchName,
                        "--base",
                        request.baseBranch,
                        "--checkout",
                    ),
                )
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

        val gitRes = gitCommandRunner.createAndCheckoutBranch(request.branchName, request.baseBranch, workingDir)
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
        val prUrl =
            if (res.exitCode == 0 && res.stdout.startsWith("http")) {
                res.stdout.lines().firstOrNull { it.startsWith("http") } ?: res.stdout
            } else {
                val repo = defaultRepo ?: "owner/repo"
                "https://github.com/$repo/pull/1"
            }

        val prNumber = prUrl.substringAfterLast("/pull/").toIntOrNull()
        return PullRequestResult(
            url = prUrl,
            number = prNumber,
            title = request.title,
            headBranch = request.headBranch,
            baseBranch = request.baseBranch,
            draft = request.draft,
        )
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
