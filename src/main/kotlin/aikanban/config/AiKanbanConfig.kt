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
data class CustomWorkflowDefinition(
    val description: String? = null,
    val steps: List<String> = emptyList(),
)

@Serializable
data class WorkflowOptionsConfig(
    val mergeMethod: String = "squash",
    val deleteBranchOnMerge: Boolean = true,
    val requestColumn: String = "REQUEST",
    val doneColumn: String = "DONE",
    val reviewColumn: String = "REVIEW",
)

@Serializable
data class AiKanbanConfig(
    val provider: String = "local-git",
    val defaultBaseBranch: String = "main",
    val repo: String? = null,
    val branchPrefix: String = "feature/",
    val token: String? = null,
    val verify: List<String> = emptyList(),
    val hooks: Map<String, List<String>> = emptyMap(),
    val workflows: Map<String, CustomWorkflowDefinition> = emptyMap(),
    val workflow: WorkflowOptionsConfig = WorkflowOptionsConfig(),
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
    var overrideGlobalConfigFile: File? = null

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    private val CONFIG_FILENAMES = listOf(".aikanban.json", "aikanban.config.json")

    fun resolveGlobalConfigPath(
        env: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name") ?: "Linux",
        userHome: String = System.getProperty("user.home") ?: ".",
    ): File {
        if (overrideGlobalConfigFile != null) {
            return overrideGlobalConfigFile!!
        }
        val envPath = env["AIKANBAN_GLOBAL_CONFIG"]
        if (!envPath.isNullOrBlank()) {
            return File(envPath)
        }

        return if (osName.contains("win", ignoreCase = true)) {
            val appData = env["APPDATA"] ?: "$userHome/AppData/Roaming"
            val sep = "\\"
            File("$appData${sep}aikanban${sep}config.json")
        } else if (osName.contains("mac", ignoreCase = true)) {
            File("$userHome/Library/Application Support/aikanban/config.json")
        } else {
            val xdgConfig = env["XDG_CONFIG_HOME"]
            if (!xdgConfig.isNullOrBlank()) {
                File("$xdgConfig/aikanban/config.json")
            } else {
                File("$userHome/.config/aikanban/config.json")
            }
        }
    }

    fun getGlobalConfigFile(): File = resolveGlobalConfigPath()

    fun findGlobalConfigFile(): File? {
        if (overrideGlobalConfigFile != null) {
            return if (overrideGlobalConfigFile!!.exists() && overrideGlobalConfigFile!!.canRead()) overrideGlobalConfigFile else null
        }
        val osFile = resolveGlobalConfigPath()
        if (osFile.isFile && osFile.canRead()) {
            return osFile
        }
        val legacyHomeFile = File(System.getProperty("user.home") ?: ".", ".aikanban.json")
        if (legacyHomeFile.isFile && legacyHomeFile.canRead()) {
            return legacyHomeFile
        }
        return null
    }

    fun loadGlobal(): AiKanbanConfig {
        val candidate = findGlobalConfigFile() ?: return AiKanbanConfig()
        return try {
            json.decodeFromString<AiKanbanConfig>(candidate.readText())
        } catch (e: Exception) {
            AiKanbanConfig()
        }
    }

    fun saveGlobal(config: AiKanbanConfig): File {
        val target = getGlobalConfigFile()
        target.parentFile?.mkdirs()
        target.writeText(json.encodeToString(config))
        return target
    }

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

    fun merge(
        global: AiKanbanConfig,
        project: AiKanbanConfig?,
    ): AiKanbanConfig {
        if (project == null) return global
        val mergedWorkflow =
            WorkflowOptionsConfig(
                mergeMethod =
                    if (project.workflow.mergeMethod.isNotBlank()) {
                        project.workflow.mergeMethod
                    } else {
                        global.workflow.mergeMethod
                    },
                deleteBranchOnMerge = project.workflow.deleteBranchOnMerge,
                requestColumn =
                    if (project.workflow.requestColumn.isNotBlank()) {
                        project.workflow.requestColumn
                    } else {
                        global.workflow.requestColumn
                    },
                doneColumn =
                    if (project.workflow.doneColumn.isNotBlank()) {
                        project.workflow.doneColumn
                    } else {
                        global.workflow.doneColumn
                    },
                reviewColumn =
                    if (project.workflow.reviewColumn.isNotBlank()) {
                        project.workflow.reviewColumn
                    } else {
                        global.workflow.reviewColumn
                    },
            )
        return AiKanbanConfig(
            provider = project.provider,
            defaultBaseBranch = project.defaultBaseBranch,
            repo = project.repo ?: global.repo,
            branchPrefix = project.branchPrefix,
            token = project.token ?: global.token ?: System.getenv("GITHUB_TOKEN"),
            verify = project.verify.ifEmpty { global.verify },
            hooks = global.hooks + project.hooks,
            workflows = global.workflows + project.workflows,
            workflow = mergedWorkflow,
        )
    }

    fun load(
        startDir: File = File("."),
        mergeGlobal: Boolean = true,
    ): AiKanbanConfig {
        val candidate = findConfigFile(startDir)
        val projectConfig =
            candidate?.let {
                try {
                    json.decodeFromString<AiKanbanConfig>(it.readText())
                } catch (e: Exception) {
                    null
                }
            }

        if (!mergeGlobal) {
            return projectConfig ?: AiKanbanConfig()
        }

        val globalConfig = loadGlobal()
        return if (projectConfig != null) merge(globalConfig, projectConfig) else globalConfig
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
