package aikanban.cli.command

import aikanban.cli.CliContext
import aikanban.cli.renderer.HumanRenderer
import aikanban.cli.renderer.JsonRenderer
import aikanban.provider.ProviderSyncRequest
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

class SyncCommand : CliktCommand(name = "sync") {
    override fun help(context: Context): String =
        "Synchronize issues/PRs from configured VCS provider (local-git, github) into Kanban tasks"

    private val cliContext by requireObject<CliContext>()

    private val repo by argument("repo", help = "Repository identifier or URL to sync").optional()
    private val url by option("--url", help = "Specific issue or pull request URL to sync")
    private val state by option("-s", "--state", help = "Issue state (open, closed, all)").default("open")
    private val tags by option("-t", "--tag", help = "Filter by label/tag (repeatable or comma-separated)").multiple()
    private val includePrs by option("--include-prs", help = "Include pull requests in synchronization").flag(default = false)
    private val targetStatus by option("-c", "--column", "--status", help = "Target Kanban column for open issues").default("TODO")
    private val token by option("--token", help = "Personal access token", envvar = "GITHUB_TOKEN")
    private val provider by option("--provider", help = "VCS provider override (local-git, github)")
    private val dryRun by option("--dry-run", help = "Preview synchronization without modifying database").flag(default = false)
    private val operator by option("-o", "--operator", help = "Operator identifier").default("cli-sync")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val parsedTags = tags.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.toSet()

        val targetUrl = url?.trim()
        val targetRepo = repo?.trim()
        val target = targetUrl ?: targetRepo

        val activeProvider = cliContext.providerFactory.resolve(provider, cliContext.config, target)

        val result =
            runBlocking {
                activeProvider.sync(
                    ProviderSyncRequest(
                        repoOrUrl = target,
                        state = state,
                        labels = parsedTags,
                        includePullRequests = includePrs,
                        targetStatus = targetStatus,
                        token = token,
                        operator = operator,
                        dryRun = dryRun,
                    ),
                )
            }

        if (isJson) {
            println(JsonRenderer.render(result))
        } else {
            val t = cliContext.terminal
            val prefix = if (dryRun) yellow("[DRY RUN] ") else ""
            t.println(bold(green("$prefix✓ Synced ${result.provider}: ${result.repo ?: "local"}")))
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
