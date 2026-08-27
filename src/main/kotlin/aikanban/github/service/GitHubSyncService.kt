package aikanban.github.service

import aikanban.github.client.GitHubClient
import aikanban.github.client.KtorGitHubClient
import aikanban.github.model.GitHubIssueDto
import aikanban.github.model.GitHubLabelDto
import aikanban.github.model.GitHubResource
import aikanban.github.model.GitHubSyncResult
import aikanban.model.Task
import aikanban.model.TaskPriority
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
    private fun mapPriority(labels: List<GitHubLabelDto>): TaskPriority {
        for (label in labels) {
            val name = label.name.lowercase()
            when {
                name == "p0" || name.contains("urgent") -> return TaskPriority.URGENT
                name == "p1" || name.contains("high") -> return TaskPriority.HIGH
                name == "p3" || name == "p4" || name.contains("low") -> return TaskPriority.LOW
            }
        }
        return TaskPriority.MEDIUM
    }

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
        val parsedRepo =
            GitHubUrlParser.parseRepository(repo)
                ?: throw IllegalArgumentException("Invalid repository format: $repo. Expected 'owner/repo' or GitHub repository URL.")

        val issues =
            gitHubClient.fetchRepositoryIssues(
                owner = parsedRepo.owner,
                repo = parsedRepo.repo,
                state = state,
                labels = labels,
                token = token,
            )

        val filteredIssues =
            if (includePullRequests) {
                issues
            } else {
                issues.filter { it.pullRequest == null }
            }

        val existingTasks = kanbanService.listTasks()
        var createdCount = 0
        var updatedCount = 0
        val processedTasks = mutableListOf<Task>()

        for (issue in filteredIssues) {
            val matchingTask =
                existingTasks.find { existing ->
                    existing.githubIssueUrl == issue.htmlUrl ||
                        (
                            existing.githubRepo.equals(parsedRepo.fullRepo, ignoreCase = true) &&
                                existing.githubIssueUrl?.endsWith("/issues/${issue.number}") == true
                        )
                }

            val priority = mapPriority(issue.labels)
            val issueTags = issue.labels.map { it.name }.toSet()
            val assignee = issue.assignee?.login

            if (matchingTask != null) {
                if (dryRun) {
                    val preview =
                        matchingTask.copy(
                            title = issue.title,
                            description = issue.body ?: matchingTask.description,
                            priority = priority,
                            assignee = assignee ?: matchingTask.assignee,
                            tags = matchingTask.tags + issueTags,
                            githubRepo = parsedRepo.fullRepo,
                            githubIssueUrl = issue.htmlUrl,
                            githubPrUrl = issue.pullRequest?.htmlUrl ?: matchingTask.githubPrUrl,
                        )
                    processedTasks.add(preview)
                } else {
                    val updated =
                        kanbanService.updateTask(
                            taskId = matchingTask.id,
                            title = issue.title,
                            description = issue.body ?: matchingTask.description,
                            priority = priority,
                            assignee = assignee ?: matchingTask.assignee,
                            tags = matchingTask.tags + issueTags,
                            githubRepo = parsedRepo.fullRepo,
                            githubIssueUrl = issue.htmlUrl,
                            githubPrUrl = issue.pullRequest?.htmlUrl ?: matchingTask.githubPrUrl,
                            operator = operator,
                            comment = "Synced from GitHub Issue #${issue.number}",
                        )

                    val finalTask =
                        if (issue.state == "closed" && updated.status != "DONE") {
                            kanbanService.moveTask(
                                taskId = updated.id,
                                toStatus = "DONE",
                                operator = operator,
                                comment = "Closed on GitHub",
                            )
                        } else {
                            updated
                        }
                    processedTasks.add(finalTask)
                }
                updatedCount++
            } else {
                val initialStatus = if (issue.state == "closed") "DONE" else targetStatus
                if (dryRun) {
                    val preview =
                        Task(
                            id = 0,
                            title = issue.title,
                            description = issue.body ?: "",
                            priority = priority,
                            assignee = assignee,
                            tags = issueTags,
                            githubRepo = parsedRepo.fullRepo,
                            githubIssueUrl = issue.htmlUrl,
                            githubPrUrl = issue.pullRequest?.htmlUrl,
                            status = initialStatus,
                            completedAt = if (issue.state == "closed") System.currentTimeMillis() else null,
                        )
                    processedTasks.add(preview)
                } else {
                    val created =
                        kanbanService.createTask(
                            title = issue.title,
                            description = issue.body ?: "",
                            priority = priority,
                            assignee = assignee,
                            tags = issueTags,
                            githubRepo = parsedRepo.fullRepo,
                            githubIssueUrl = issue.htmlUrl,
                            status = targetStatus,
                            operator = operator,
                        )
                    val taskAfterPr =
                        if (issue.pullRequest?.htmlUrl != null) {
                            kanbanService.updateTask(
                                taskId = created.id,
                                githubPrUrl = issue.pullRequest.htmlUrl,
                                operator = operator,
                            )
                        } else {
                            created
                        }
                    val finalTask =
                        if (issue.state == "closed") {
                            kanbanService.moveTask(
                                taskId = taskAfterPr.id,
                                toStatus = "DONE",
                                operator = operator,
                                comment = "Closed on GitHub",
                            )
                        } else {
                            taskAfterPr
                        }
                    processedTasks.add(finalTask)
                }
                createdCount++
            }
        }

        return GitHubSyncResult(
            repo = parsedRepo.fullRepo,
            totalFetched = filteredIssues.size,
            createdCount = createdCount,
            updatedCount = updatedCount,
            skippedCount = issues.size - filteredIssues.size,
            tasks = processedTasks,
        )
    }

    override suspend fun syncUrl(
        url: String,
        targetStatus: String,
        token: String?,
        operator: String,
        dryRun: Boolean,
    ): GitHubSyncResult {
        val resource =
            GitHubUrlParser.parse(url)
                ?: throw IllegalArgumentException("Invalid GitHub URL: $url")

        return when (resource) {
            is GitHubResource.Issue -> {
                val issue =
                    gitHubClient.fetchIssue(resource.owner, resource.repo, resource.number, token)
                        ?: throw IllegalArgumentException("Issue #${resource.number} not found in ${resource.fullRepo}")
                syncSingleIssue(resource.fullRepo, issue, targetStatus, operator, dryRun)
            }
            is GitHubResource.PullRequest -> {
                val issue =
                    gitHubClient.fetchIssue(resource.owner, resource.repo, resource.number, token)
                        ?: throw IllegalArgumentException("Pull request #${resource.number} not found in ${resource.fullRepo}")
                syncSingleIssue(resource.fullRepo, issue, targetStatus, operator, dryRun)
            }
            is GitHubResource.Repository -> {
                syncRepository(
                    repo = resource.fullRepo,
                    targetStatus = targetStatus,
                    token = token,
                    operator = operator,
                    dryRun = dryRun,
                )
            }
        }
    }

    private fun syncSingleIssue(
        fullRepo: String,
        issue: GitHubIssueDto,
        targetStatus: String,
        operator: String,
        dryRun: Boolean,
    ): GitHubSyncResult {
        val existingTasks = kanbanService.listTasks()
        val matchingTask =
            existingTasks.find { existing ->
                existing.githubIssueUrl == issue.htmlUrl ||
                    (
                        existing.githubRepo.equals(fullRepo, ignoreCase = true) &&
                            existing.githubIssueUrl?.endsWith("/issues/${issue.number}") == true
                    )
            }

        val priority = mapPriority(issue.labels)
        val issueTags = issue.labels.map { it.name }.toSet()
        val assignee = issue.assignee?.login

        val tasks = mutableListOf<Task>()
        var createdCount = 0
        var updatedCount = 0

        if (matchingTask != null) {
            if (dryRun) {
                tasks.add(
                    matchingTask.copy(
                        title = issue.title,
                        description = issue.body ?: matchingTask.description,
                        priority = priority,
                        assignee = assignee ?: matchingTask.assignee,
                        tags = matchingTask.tags + issueTags,
                        githubRepo = fullRepo,
                        githubIssueUrl = issue.htmlUrl,
                        githubPrUrl = issue.pullRequest?.htmlUrl ?: matchingTask.githubPrUrl,
                    ),
                )
            } else {
                val updated =
                    kanbanService.updateTask(
                        taskId = matchingTask.id,
                        title = issue.title,
                        description = issue.body ?: matchingTask.description,
                        priority = priority,
                        assignee = assignee ?: matchingTask.assignee,
                        tags = matchingTask.tags + issueTags,
                        githubRepo = fullRepo,
                        githubIssueUrl = issue.htmlUrl,
                        githubPrUrl = issue.pullRequest?.htmlUrl ?: matchingTask.githubPrUrl,
                        operator = operator,
                        comment = "Synced from GitHub #${issue.number}",
                    )
                val finalTask =
                    if (issue.state == "closed" && updated.status != "DONE") {
                        kanbanService.moveTask(
                            taskId = updated.id,
                            toStatus = "DONE",
                            operator = operator,
                            comment = "Closed on GitHub",
                        )
                    } else {
                        updated
                    }
                tasks.add(finalTask)
            }
            updatedCount++
        } else {
            val initialStatus = if (issue.state == "closed") "DONE" else targetStatus
            if (dryRun) {
                tasks.add(
                    Task(
                        id = 0,
                        title = issue.title,
                        description = issue.body ?: "",
                        priority = priority,
                        assignee = assignee,
                        tags = issueTags,
                        githubRepo = fullRepo,
                        githubIssueUrl = issue.htmlUrl,
                        githubPrUrl = issue.pullRequest?.htmlUrl,
                        status = initialStatus,
                        completedAt = if (issue.state == "closed") System.currentTimeMillis() else null,
                    ),
                )
            } else {
                val created =
                    kanbanService.createTask(
                        title = issue.title,
                        description = issue.body ?: "",
                        priority = priority,
                        assignee = assignee,
                        tags = issueTags,
                        githubRepo = fullRepo,
                        githubIssueUrl = issue.htmlUrl,
                        status = targetStatus,
                        operator = operator,
                    )
                val taskAfterPr =
                    if (issue.pullRequest?.htmlUrl != null) {
                        kanbanService.updateTask(
                            taskId = created.id,
                            githubPrUrl = issue.pullRequest.htmlUrl,
                            operator = operator,
                        )
                    } else {
                        created
                    }
                val finalTask =
                    if (issue.state == "closed") {
                        kanbanService.moveTask(
                            taskId = taskAfterPr.id,
                            toStatus = "DONE",
                            operator = operator,
                            comment = "Closed on GitHub",
                        )
                    } else {
                        taskAfterPr
                    }
                tasks.add(finalTask)
            }
            createdCount++
        }

        return GitHubSyncResult(
            repo = fullRepo,
            totalFetched = 1,
            createdCount = createdCount,
            updatedCount = updatedCount,
            skippedCount = 0,
            tasks = tasks,
        )
    }
}
