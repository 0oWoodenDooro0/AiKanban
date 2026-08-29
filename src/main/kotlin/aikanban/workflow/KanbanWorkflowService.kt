package aikanban.workflow

import aikanban.config.AiKanbanConfig
import aikanban.model.Task
import aikanban.model.TaskPriority
import aikanban.provider.AddIssueCommentRequest
import aikanban.provider.BranchResult
import aikanban.provider.CreateBranchRequest
import aikanban.provider.CreateIssueRequest
import aikanban.provider.CreatePullRequestRequest
import aikanban.provider.DefaultGitCommandRunner
import aikanban.provider.GitCommandRunner
import aikanban.provider.IssueResult
import aikanban.provider.ProviderFactory
import aikanban.provider.PullRequestResult
import aikanban.service.KanbanService
import kotlinx.serialization.Serializable

@Serializable
data class StartIssueRequest(
    val title: String,
    val description: String = "",
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val tags: Set<String> = emptySet(),
    val branchName: String? = null,
    val baseBranch: String = "main",
    val plan: String? = null,
    val assignee: String? = null,
    val operator: String = "workflow",
    val providerName: String? = null,
    val dryRun: Boolean = false,
)

@Serializable
data class StartIssueResult(
    val task: Task,
    val issue: IssueResult,
    val branch: BranchResult,
)

@Serializable
data class SubmitPrRequest(
    val taskId: Int,
    val title: String? = null,
    val body: String? = null,
    val headBranch: String? = null,
    val baseBranch: String = "main",
    val draft: Boolean = false,
    val operator: String = "workflow",
    val providerName: String? = null,
    val dryRun: Boolean = false,
)

@Serializable
data class SubmitPrResult(
    val task: Task,
    val pr: PullRequestResult,
)

interface KanbanWorkflowService {
    suspend fun startIssue(request: StartIssueRequest): StartIssueResult

    suspend fun submitPr(request: SubmitPrRequest): SubmitPrResult
}

class DefaultKanbanWorkflowService(
    private val kanbanService: KanbanService,
    private val providerFactory: ProviderFactory,
    private val config: AiKanbanConfig = AiKanbanConfig(),
    private val gitCommandRunner: GitCommandRunner = DefaultGitCommandRunner(),
) : KanbanWorkflowService {
    companion object {
        fun slugify(text: String): String {
            return text
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(50)
                .trim('-')
        }
    }

    override suspend fun startIssue(request: StartIssueRequest): StartIssueResult {
        val provider = providerFactory.resolve(request.providerName, config)
        val branchName =
            request.branchName?.trim()?.takeIf { it.isNotBlank() }
                ?: "${config.branchPrefix}${slugify(request.title)}"

        val issueResult =
            provider.createIssue(
                CreateIssueRequest(
                    title = request.title,
                    body = request.description,
                    labels = request.tags,
                    priority = request.priority,
                    assignee = request.assignee,
                ),
            )

        if (request.dryRun) {
            val previewTask =
                Task(
                    id = 0,
                    title = request.title,
                    description = request.description,
                    priority = request.priority,
                    assignee = request.assignee,
                    tags = request.tags,
                    githubIssueUrl = issueResult.url,
                    status = "TODO",
                )
            val previewBranch =
                BranchResult(
                    branchName = branchName,
                    baseBranch = request.baseBranch,
                    created = true,
                    linkedIssueUrl = issueResult.url,
                    message = "Dry-run branch preview",
                )
            return StartIssueResult(previewTask, issueResult, previewBranch)
        }

        val task =
            kanbanService.createTask(
                title = request.title,
                description = request.description,
                priority = request.priority,
                assignee = request.assignee,
                tags = request.tags,
                githubIssueUrl = issueResult.url,
                status = "TODO",
                operator = request.operator,
            )

        if (!request.plan.isNullOrBlank()) {
            provider.addComment(
                AddIssueCommentRequest(
                    issueIdOrUrl = issueResult.url ?: issueResult.id,
                    comment = request.plan,
                ),
            )
            kanbanService.addComment(
                taskId = task.id,
                operator = request.operator,
                comment = "Attached implementation plan",
            )
        }

        val branchResult =
            provider.createBranch(
                CreateBranchRequest(
                    branchName = branchName,
                    baseBranch = request.baseBranch,
                    issueIdOrUrl = issueResult.url,
                ),
            )

        kanbanService.addComment(
            taskId = task.id,
            operator = request.operator,
            comment = "Created and switched to branch $branchName",
        )

        return StartIssueResult(
            task = kanbanService.getTask(task.id),
            issue = issueResult,
            branch = branchResult,
        )
    }

    override suspend fun submitPr(request: SubmitPrRequest): SubmitPrResult {
        val provider = providerFactory.resolve(request.providerName, config)
        val task = kanbanService.getTask(request.taskId)

        val headBranch =
            request.headBranch?.trim()?.takeIf { it.isNotBlank() }
                ?: gitCommandRunner.getCurrentBranch()
        val prTitle = request.title?.trim()?.takeIf { it.isNotBlank() } ?: task.title
        val prBody = request.body ?: task.description

        if (request.dryRun) {
            val previewPr =
                PullRequestResult(
                    url = "https://preview/pr/${task.id}",
                    number = task.id,
                    title = prTitle,
                    headBranch = headBranch,
                    baseBranch = request.baseBranch,
                    draft = request.draft,
                )
            return SubmitPrResult(task, previewPr)
        }

        val prResult =
            provider.createPullRequest(
                CreatePullRequestRequest(
                    title = prTitle,
                    body = prBody,
                    headBranch = headBranch,
                    baseBranch = request.baseBranch,
                    draft = request.draft,
                ),
            )

        val updatedTask =
            kanbanService.moveTask(
                taskId = task.id,
                toStatus = "REVIEW",
                operator = request.operator,
                comment = "Submitted pull request: ${prResult.url}",
                prUrl = prResult.url,
            )

        return SubmitPrResult(
            task = updatedTask,
            pr = prResult,
        )
    }
}
