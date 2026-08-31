package aikanban.config

import aikanban.cli.prompt.InteractivePrompter
import aikanban.github.service.GitHubUrlParser
import aikanban.provider.DefaultGitCommandRunner
import aikanban.provider.GitCommandRunner
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AiKanbanConfig(
    val provider: String = "local-git",
    val defaultBaseBranch: String = "main",
    val repo: String? = null,
    val branchPrefix: String = "feature/",
    val token: String? = null,
)

@Serializable
data class ProbedGitInfo(
    val isGitRepository: Boolean,
    val remoteUrl: String? = null,
    val detectedRepo: String? = null,
    val suggestedProvider: String = "local-git",
    val defaultBaseBranch: String = "main",
)

object AiKanbanConfigLoader {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    private val CONFIG_FILENAMES = listOf(".aikanban.json", "aikanban.config.json")

    fun findConfigFile(startDir: File = File(".")): File? {
        var current: File? = startDir.absoluteFile
        while (current != null) {
            for (filename in CONFIG_FILENAMES) {
                val candidate = File(current, filename)
                if (candidate.isFile && candidate.canRead()) {
                    return candidate
                }
            }
            current = current.parentFile
        }
        return null
    }

    fun hasConfigFile(startDir: File = File(".")): Boolean = findConfigFile(startDir) != null

    fun load(startDir: File = File(".")): AiKanbanConfig {
        val candidate = findConfigFile(startDir) ?: return AiKanbanConfig()
        return try {
            json.decodeFromString<AiKanbanConfig>(candidate.readText())
        } catch (e: Exception) {
            AiKanbanConfig()
        }
    }

    fun save(
        config: AiKanbanConfig,
        dir: File = File("."),
    ): File {
        val target = File(dir, ".aikanban.json")
        target.writeText(json.encodeToString(config))
        return target
    }

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
        val existingFile = findConfigFile(workingDir)
        if (existingFile != null && !forcePrompt) {
            return load(workingDir)
        }

        if (!prompter.isInteractive()) {
            return if (existingFile != null) load(workingDir) else AiKanbanConfig()
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
            save(config, workingDir)
        }

        return config
    }
}
