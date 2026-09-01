package aikanban.provider.ingestion

import aikanban.model.Task
import aikanban.model.TaskPriority
import aikanban.provider.ProviderSyncResult
import aikanban.service.KanbanService

data class RawIssueData(
    val id: String,
    val number: Int? = null,
    val title: String,
    val body: String? = null,
    val state: String = "open",
    val htmlUrl: String,
    val assignee: String? = null,
    val labels: List<String> = emptyList(),
    val isPullRequest: Boolean = false,
    val prUrl: String? = null,
)

interface IssueIngestionPipeline {
    suspend fun ingest(
        issues: List<RawIssueData>,
        repo: String?,
        targetStatus: String = "TODO",
        operator: String = "sync",
        dryRun: Boolean = false,
        providerName: String = "vcs",
        totalFetched: Int = issues.size,
        skippedCount: Int = 0,
    ): ProviderSyncResult
}

class DefaultIssueIngestionPipeline(
    private val kanbanService: KanbanService,
) : IssueIngestionPipeline {
    private fun mapPriority(labels: List<String>): TaskPriority {
        for (label in labels) {
            val name = label.lowercase()
            when {
                name == "p0" || name.contains("urgent") -> return TaskPriority.URGENT
                name == "p1" || name.contains("high") -> return TaskPriority.HIGH
                name == "p3" || name == "p4" || name.contains("low") -> return TaskPriority.LOW
            }
        }
        return TaskPriority.MEDIUM
    }

    override suspend fun ingest(
        issues: List<RawIssueData>,
        repo: String?,
        targetStatus: String,
        operator: String,
        dryRun: Boolean,
        providerName: String,
        totalFetched: Int,
        skippedCount: Int,
    ): ProviderSyncResult {
        val existingTasks = kanbanService.listTasks(includeCompleted = true)
        var createdCount = 0
        var updatedCount = 0
        val processedTasks = mutableListOf<Task>()

        for (issue in issues) {
            val matchingTask =
                existingTasks.find { existing ->
                    existing.githubIssueUrl == issue.htmlUrl ||
                        (
                            !repo.isNullOrBlank() &&
                                existing.githubRepo.equals(repo, ignoreCase = true) &&
                                issue.number != null &&
                                existing.githubIssueUrl?.endsWith("/issues/${issue.number}") == true
                        )
                }

            val priority = mapPriority(issue.labels)
            val issueTags = issue.labels.toSet()
            val assignee = issue.assignee
            val prUrl = if (issue.isPullRequest) issue.prUrl ?: issue.htmlUrl else issue.prUrl

            if (matchingTask != null) {
                if (dryRun) {
                    val preview =
                        matchingTask.copy(
                            title = issue.title,
                            description = issue.body ?: matchingTask.description,
                            priority = priority,
                            assignee = assignee ?: matchingTask.assignee,
                            tags = matchingTask.tags + issueTags,
                            githubRepo = repo ?: matchingTask.githubRepo,
                            githubIssueUrl = issue.htmlUrl,
                            githubPrUrl = prUrl ?: matchingTask.githubPrUrl,
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
                            githubRepo = repo ?: matchingTask.githubRepo,
                            githubIssueUrl = issue.htmlUrl,
                            githubPrUrl = prUrl ?: matchingTask.githubPrUrl,
                            operator = operator,
                            comment =
                                if (issue.number != null) {
                                    "Synced from $providerName #${issue.number}"
                                } else {
                                    "Synced from $providerName"
                                },
                        )

                    val finalTask =
                        if (issue.state == "closed" && updated.status != "DONE") {
                            kanbanService.moveTask(
                                taskId = updated.id,
                                toStatus = "DONE",
                                operator = operator,
                                comment = "Closed on $providerName",
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
                            githubRepo = repo,
                            githubIssueUrl = issue.htmlUrl,
                            githubPrUrl = prUrl,
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
                            githubRepo = repo,
                            githubIssueUrl = issue.htmlUrl,
                            status = targetStatus,
                            operator = operator,
                        )
                    val taskAfterPr =
                        if (!prUrl.isNullOrBlank()) {
                            kanbanService.updateTask(
                                taskId = created.id,
                                githubPrUrl = prUrl,
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
                                comment = "Closed on $providerName",
                            )
                        } else {
                            taskAfterPr
                        }
                    processedTasks.add(finalTask)
                }
                createdCount++
            }
        }

        return ProviderSyncResult(
            provider = providerName,
            repo = repo,
            totalFetched = totalFetched,
            createdCount = createdCount,
            updatedCount = updatedCount,
            skippedCount = skippedCount,
            tasks = processedTasks,
        )
    }
}
