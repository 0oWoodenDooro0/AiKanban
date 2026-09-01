package aikanban.cli.setup

import aikanban.cli.CliContext
import aikanban.cli.prompt.InteractivePrompter
import aikanban.config.AiKanbanConfig
import aikanban.config.AiKanbanConfigLoader
import aikanban.github.service.GitHubUrlParser
import aikanban.provider.DefaultGitCommandRunner
import aikanban.provider.GitCommandRunner
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ProbedGitInfo(
    val isGitRepository: Boolean,
    val remoteUrl: String? = null,
    val detectedRepo: String? = null,
    val suggestedProvider: String = "local-git",
    val defaultBaseBranch: String = "main",
)

object CliSetupWizard {
    fun probeGitConfig(
        workingDir: File = File("."),
        gitCommandRunner: GitCommandRunner = DefaultGitCommandRunner(),
    ): ProbedGitInfo {
        val isRepo = gitCommandRunner.isGitRepository(workingDir)
        if (!isRepo) {
            return ProbedGitInfo(
                isGitRepository = false,
                suggestedProvider = "local-git",
                defaultBaseBranch = "main",
            )
        }

        val remoteUrl = gitCommandRunner.getRemoteUrl("origin", workingDir)
        val parsedRepo =
            remoteUrl?.let {
                GitHubUrlParser.parseRepository(it)?.let { r -> "${r.owner}/${r.repo}" }
            }
        val suggestedProvider = if (parsedRepo != null) "github" else "local-git"

        return ProbedGitInfo(
            isGitRepository = true,
            remoteUrl = remoteUrl,
            detectedRepo = parsedRepo,
            suggestedProvider = suggestedProvider,
            defaultBaseBranch = "main",
        )
    }

    fun ensureProviderConfig(
        workingDir: File = File("."),
        prompter: InteractivePrompter,
        gitCommandRunner: GitCommandRunner = DefaultGitCommandRunner(),
        forcePrompt: Boolean = false,
    ): AiKanbanConfig {
        val existingFile = AiKanbanConfigLoader.findConfigFile(workingDir)
        if (existingFile != null && !forcePrompt) {
            return AiKanbanConfigLoader.load(workingDir)
        }

        if (!prompter.isInteractive()) {
            return if (existingFile != null) AiKanbanConfigLoader.load(workingDir) else AiKanbanConfig()
        }

        val probed = probeGitConfig(workingDir, gitCommandRunner)
        val chosenProvider =
            prompter.promptChoice(
                message = "Select Git Provider",
                choices = listOf("github", "local-git"),
                default = probed.suggestedProvider,
            ) ?: probed.suggestedProvider

        val config =
            if (chosenProvider.equals("github", ignoreCase = true)) {
                val repoInput = prompter.prompt("GitHub Repository (owner/repo)", default = probed.detectedRepo)
                val baseBranchInput = prompter.prompt("Default Base Branch", default = probed.defaultBaseBranch) ?: "main"
                val branchPrefixInput = prompter.prompt("Branch Prefix", default = "feature/") ?: "feature/"
                AiKanbanConfig(
                    provider = "github",
                    repo = repoInput?.takeIf { it.isNotBlank() },
                    defaultBaseBranch = baseBranchInput,
                    branchPrefix = branchPrefixInput,
                )
            } else {
                val baseBranchInput = prompter.prompt("Default Base Branch", default = probed.defaultBaseBranch) ?: "main"
                val branchPrefixInput = prompter.prompt("Branch Prefix", default = "feature/") ?: "feature/"
                AiKanbanConfig(
                    provider = "local-git",
                    defaultBaseBranch = baseBranchInput,
                    branchPrefix = branchPrefixInput,
                )
            }

        val saveConfirmed = prompter.confirm("Save configuration to .aikanban.json?", default = true)
        if (saveConfirmed) {
            AiKanbanConfigLoader.save(config, workingDir)
        }

        return config
    }

    fun ensureConfig(
        cliContext: CliContext,
        overrideProvider: String? = null,
        isJson: Boolean = false,
        bypassIfTargetSpecified: Boolean = false,
    ): AiKanbanConfig {
        val shouldPrompt =
            overrideProvider == null &&
                !bypassIfTargetSpecified &&
                !isJson &&
                !AiKanbanConfigLoader.hasConfigFile(cliContext.workingDir)

        return if (shouldPrompt) {
            ensureProviderConfig(
                workingDir = cliContext.workingDir,
                prompter = cliContext.prompter,
                gitCommandRunner = cliContext.gitCommandRunner,
            )
        } else {
            cliContext.config
        }
    }
}
