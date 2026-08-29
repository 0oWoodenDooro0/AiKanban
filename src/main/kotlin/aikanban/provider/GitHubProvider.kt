package aikanban.provider

import aikanban.github.model.GitHubResource
import aikanban.github.service.DefaultGitHubSyncService
import aikanban.github.service.GitHubSyncService
import aikanban.github.service.GitHubUrlParser
import aikanban.service.KanbanService
import java.io.File
import java.util.concurrent.TimeUnit

class GitHubProvider(
    private val kanbanService: KanbanService,
    private val gitHubSyncService: GitHubSyncService = DefaultGitHubSyncService(kanbanService),
    private val gitCommandRunner: GitCommandRunner = DefaultGitCommandRunner(),
    private val workingDir: File = File("."),
    private val defaultRepo: String? = null,
    private val token: String? = null,
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

        val parsed = GitHubUrlParser.parse(target)
        val syncResult =
            if (parsed is GitHubResource.Issue || parsed is GitHubResource.PullRequest) {
                gitHubSyncService.syncUrl(
                    url = target,
                    targetStatus = request.targetStatus,
                    token = request.token ?: token,
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
                    token = request.token ?: token,
                    operator = request.operator,
                    dryRun = request.dryRun,
                )
            }

        return ProviderSyncResult(
            provider = name,
            repo = syncResult.repo,
            totalFetched = syncResult.totalFetched,
            createdCount = syncResult.createdCount,
            updatedCount = syncResult.updatedCount,
            skippedCount = syncResult.skippedCount,
            tasks = syncResult.tasks,
        )
    }
}
