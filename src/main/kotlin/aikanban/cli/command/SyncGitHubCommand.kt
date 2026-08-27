package aikanban.cli.command

import aikanban.cli.CliContext
import aikanban.cli.renderer.HumanRenderer
import aikanban.cli.renderer.JsonRenderer
import aikanban.github.model.GitHubResource
import aikanban.github.service.GitHubUrlParser
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import kotlinx.coroutines.runBlocking

class SyncGitHubCommand : CliktCommand(name = "sync-github") {
    override fun help(context: Context): String = "Synchronize GitHub repository issues or a specific issue/PR URL into Kanban tasks"

    private val cliContext by requireObject<CliContext>()

    private val repo by argument("repo", help = "GitHub repository ('owner/repo') or repository/issue URL").optional()
    private val url by option("--url", help = "Specific GitHub issue or pull request URL to sync")
    private val state by option("-s", "--state", help = "Issue state (open, closed, all)").default("open")
    private val tags by option("-t", "--tag", help = "Filter by GitHub label/tag (repeatable or comma-separated)").multiple()
    private val includePrs by option("--include-prs", help = "Include pull requests in synchronization").flag(default = false)
    private val targetStatus by option("-c", "--column", "--status", help = "Target Kanban column for open issues").default("TODO")
    private val token by option("--token", help = "GitHub personal access token", envvar = "GITHUB_TOKEN")
    private val dryRun by option("--dry-run", help = "Preview synchronization without modifying database").flag(default = false)
    private val operator by option("-o", "--operator", help = "Operator identifier").default("cli-github-sync")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val parsedTags = tags.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.toSet()

        val targetUrl = url?.trim()
        val targetRepo = repo?.trim()

        if (targetUrl.isNullOrBlank() && targetRepo.isNullOrBlank()) {
            throw IllegalArgumentException("Please specify a GitHub repository ('owner/repo') or an issue URL via --url.")
        }

        val result =
            runBlocking {
                if (!targetUrl.isNullOrBlank()) {
                    cliContext.gitHubSyncService.syncUrl(
                        url = targetUrl,
                        targetStatus = targetStatus,
                        token = token,
                        operator = operator,
                        dryRun = dryRun,
                    )
                } else {
                    val parsed = GitHubUrlParser.parse(targetRepo!!)
                    if (parsed is GitHubResource.Issue || parsed is GitHubResource.PullRequest) {
                        cliContext.gitHubSyncService.syncUrl(
                            url = targetRepo,
                            targetStatus = targetStatus,
                            token = token,
                            operator = operator,
                            dryRun = dryRun,
                        )
                    } else {
                        cliContext.gitHubSyncService.syncRepository(
                            repo = targetRepo,
                            state = state,
                            labels = parsedTags,
                            includePullRequests = includePrs,
                            targetStatus = targetStatus,
                            token = token,
                            operator = operator,
                            dryRun = dryRun,
                        )
                    }
                }
            }

        if (isJson) {
            println(JsonRenderer.render(result))
        } else {
            val t = cliContext.terminal
            val prefix = if (dryRun) yellow("[DRY RUN] ") else ""
            t.println(bold(green("$prefix✓ Synced GitHub: ${result.repo}")))
            val summaryMsg =
                "Fetched ${result.totalFetched} items: ${result.createdCount} created, " +
                    "${result.updatedCount} updated, ${result.skippedCount} skipped."
            t.println(cyan(summaryMsg))

            if (result.tasks.isNotEmpty()) {
                HumanRenderer.renderTaskList(t, result.tasks)
            }
        }
    }
}
