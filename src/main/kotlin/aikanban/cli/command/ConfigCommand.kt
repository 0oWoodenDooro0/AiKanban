package aikanban.cli.command

import aikanban.cli.CliContext
import aikanban.cli.renderer.JsonRenderer
import aikanban.config.AiKanbanConfig
import aikanban.config.AiKanbanConfigLoader
import aikanban.config.WorkflowOptionsConfig
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class ConfigShowResult(
    val configFile: String?,
    val globalConfigFile: String? = null,
    val isGlobal: Boolean = false,
    val provider: String,
    val defaultBaseBranch: String,
    val repo: String?,
    val branchPrefix: String,
    val tokenConfigured: Boolean,
    val verify: List<String> = emptyList(),
    val hooks: Map<String, List<String>> = emptyMap(),
    val workflows: List<String> = emptyList(),
    val workflow: WorkflowOptionsConfig = WorkflowOptionsConfig(),
)

class ConfigCommand : CliktCommand(name = "config") {
    override fun help(context: Context): String = "Manage AiKanban VCS provider, project, and global configuration"

    private val cliContext by requireObject<CliContext>()
    private val global by option("-g", "--global", help = "Manage or display global configuration").flag(default = false)
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    init {
        subcommands(
            ConfigShowCommand(),
            ConfigInitCommand(),
            ConfigSetCommand(),
        )
    }

    override fun run() {
        if (currentContext.invokedSubcommand == null) {
            renderConfig(cliContext, isGlobal = global, isJson = json || cliContext.jsonOutput)
        }
    }

    companion object {
        fun renderConfig(
            cliContext: CliContext,
            isGlobal: Boolean,
            isJson: Boolean,
        ) {
            val workingDir = cliContext.workingDir
            val projectFile = AiKanbanConfigLoader.findConfigFile(workingDir)
            val globalFile = AiKanbanConfigLoader.findGlobalConfigFile()
            val targetGlobalFile = AiKanbanConfigLoader.getGlobalConfigFile()

            val config =
                if (isGlobal) {
                    AiKanbanConfigLoader.loadGlobal()
                } else {
                    AiKanbanConfigLoader.load(workingDir)
                }

            val displayedConfigFile = if (isGlobal) (globalFile ?: targetGlobalFile).absolutePath else projectFile?.absolutePath

            if (isJson) {
                val result =
                    ConfigShowResult(
                        configFile = displayedConfigFile,
                        globalConfigFile = (globalFile ?: targetGlobalFile).absolutePath,
                        isGlobal = isGlobal,
                        provider = config.provider,
                        defaultBaseBranch = config.defaultBaseBranch,
                        repo = config.repo,
                        branchPrefix = config.branchPrefix,
                        tokenConfigured = !config.token.isNullOrBlank(),
                        verify = config.verify,
                        hooks = config.hooks,
                        workflows = config.workflows.keys.toList(),
                        workflow = config.workflow,
                    )
                println(JsonRenderer.render(result))
            } else {
                val t = cliContext.terminal
                val title = if (isGlobal) "AiKanban Global Configuration" else "AiKanban Configuration (Merged Active)"
                t.println(bold(cyan(title)))
                if (isGlobal) {
                    val fileDisplay =
                        if (globalFile != null) {
                            green(
                                globalFile.absolutePath,
                            )
                        } else {
                            yellow("${targetGlobalFile.absolutePath} (not created yet)")
                        }
                    t.println("  • Global config file: $fileDisplay")
                } else {
                    val fileDisplay =
                        if (projectFile != null) {
                            green(
                                projectFile.absolutePath,
                            )
                        } else {
                            yellow("(none, using defaults & global)")
                        }
                    val globalDisplay = if (globalFile != null) green(globalFile.absolutePath) else yellow("(none)")
                    t.println("  • Project config file: $fileDisplay")
                    t.println("  • Global config file:  $globalDisplay")
                }
                t.println("  • Provider: ${blue(config.provider)}")
                t.println("  • Default base branch: ${config.defaultBaseBranch}")
                t.println("  • Repository: ${config.repo ?: "(not set)"}")
                t.println("  • Branch prefix: ${config.branchPrefix}")
                t.println("  • Token configured: ${if (!config.token.isNullOrBlank()) "yes" else "no"}")
                t.println(
                    "  • Workflow options: mergeMethod=${config.workflow.mergeMethod}, " +
                        "deleteBranchOnMerge=${config.workflow.deleteBranchOnMerge}, " +
                        "requestColumn=${config.workflow.requestColumn}, doneColumn=${config.workflow.doneColumn}",
                )
                if (config.verify.isNotEmpty()) {
                    t.println("  • Verify commands: ${config.verify.joinToString(", ")}")
                }
                if (config.hooks.isNotEmpty()) {
                    t.println("  • Hooks: ${config.hooks.keys.joinToString(", ")}")
                }
                if (config.workflows.isNotEmpty()) {
                    t.println("  • Custom workflows: ${config.workflows.keys.joinToString(", ")}")
                }
            }
        }
    }
}

class ConfigShowCommand : CliktCommand(name = "show") {
    override fun help(context: Context): String = "Display current active or global configuration and file locations"

    private val cliContext by requireObject<CliContext>()
    private val global by option("-g", "--global", help = "Display global configuration").flag(default = false)
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        ConfigCommand.renderConfig(cliContext, isGlobal = global, isJson = json || cliContext.jsonOutput)
    }
}

class ConfigInitCommand : CliktCommand(name = "init") {
    override fun help(context: Context): String = "Initialize or reconfigure project or global configuration"

    private val cliContext by requireObject<CliContext>()

    private val global by option("-g", "--global", help = "Initialize global configuration").flag(default = false)
    private val provider by option("--provider", help = "VCS Provider (local-git, github)")
    private val repo by option("--repo", help = "GitHub repository (owner/repo)")
    private val base by option("--base", help = "Default base branch (e.g. main)")
    private val prefix by option("--prefix", help = "Branch prefix (e.g. feature/)")
    private val token by option("--token", help = "Personal access token")
    private val force by option("-f", "--force", help = "Overwrite existing configuration").flag(default = false)
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val workingDir = cliContext.workingDir

        val hasDirectFlags = provider != null || repo != null || base != null || prefix != null || token != null

        val config =
            if (global) {
                val current = if (!force) AiKanbanConfigLoader.loadGlobal() else AiKanbanConfig()
                val updated =
                    current.copy(
                        provider = provider ?: current.provider,
                        repo = repo ?: current.repo,
                        defaultBaseBranch = base ?: current.defaultBaseBranch,
                        branchPrefix = prefix ?: current.branchPrefix,
                        token = token ?: current.token,
                    )
                val targetFile = AiKanbanConfigLoader.saveGlobal(updated)
                if (!isJson) {
                    val t = cliContext.terminal
                    t.println(bold(green("✓ Initialized global configuration in ${targetFile.absolutePath}")))
                    t.println("  • Provider: ${blue(updated.provider)}")
                    t.println("  • Base branch: ${updated.defaultBaseBranch}")
                }
                updated
            } else {
                val configFile = File(workingDir, ".aikanban.json")
                if (hasDirectFlags) {
                    val current =
                        if (configFile.exists() && !force) {
                            AiKanbanConfigLoader.load(
                                workingDir,
                                mergeGlobal = false,
                            )
                        } else {
                            AiKanbanConfig()
                        }
                    val updated =
                        current.copy(
                            provider = provider ?: current.provider,
                            repo = repo ?: current.repo,
                            defaultBaseBranch = base ?: current.defaultBaseBranch,
                            branchPrefix = prefix ?: current.branchPrefix,
                            token = token ?: current.token,
                        )
                    AiKanbanConfigLoader.save(updated, workingDir)
                    if (!isJson) {
                        val t = cliContext.terminal
                        t.println(bold(green("✓ Initialized configuration in ${File(workingDir, ".aikanban.json").absolutePath}")))
                        t.println("  • Provider: ${blue(updated.provider)}")
                        if (updated.repo != null) {
                            t.println("  • Repo: ${updated.repo}")
                        }
                        t.println("  • Base branch: ${updated.defaultBaseBranch}")
                    }
                    updated
                } else {
                    AiKanbanConfigLoader.ensureProviderConfig(
                        workingDir = workingDir,
                        prompter = cliContext.prompter,
                        gitCommandRunner = cliContext.gitCommandRunner,
                        forcePrompt = force,
                    )
                }
            }

        if (isJson) {
            println(JsonRenderer.render(config))
        }
    }
}

class ConfigSetCommand : CliktCommand(name = "set") {
    override fun help(context: Context): String = "Set a configuration property in project or global configuration"

    private val cliContext by requireObject<CliContext>()

    private val global by option("-g", "--global", help = "Set property in global configuration").flag(default = false)
    private val key by argument(
        "key",
        help = "Configuration key (provider, repo, defaultBaseBranch/base, branchPrefix/prefix, token, verify, workflow.*)",
    )
    private val value by argument("value", help = "Configuration value")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val workingDir = cliContext.workingDir
        val current = if (global) AiKanbanConfigLoader.loadGlobal() else AiKanbanConfigLoader.load(workingDir, mergeGlobal = false)

        val updated =
            when (key.lowercase()) {
                "provider" -> current.copy(provider = value)
                "repo", "repository" -> current.copy(repo = value.takeIf { it.isNotBlank() })
                "defaultbasebranch", "base", "branch" -> current.copy(defaultBaseBranch = value)
                "branchprefix", "prefix" -> current.copy(branchPrefix = value)
                "token" -> current.copy(token = value.takeIf { it.isNotBlank() })
                "verify" -> current.copy(verify = value.split(",").map { it.trim() }.filter { it.isNotBlank() })
                "workflow.mergemethod", "mergemethod" -> current.copy(workflow = current.workflow.copy(mergeMethod = value))
                "workflow.deletebranchonmerge", "deletebranchonmerge", "deletebranch" ->
                    current.copy(workflow = current.workflow.copy(deleteBranchOnMerge = value.toBooleanStrictOrNull() ?: true))
                "workflow.requestcolumn", "requestcolumn" -> current.copy(workflow = current.workflow.copy(requestColumn = value))
                "workflow.donecolumn", "donecolumn" -> current.copy(workflow = current.workflow.copy(doneColumn = value))
                "workflow.reviewcolumn", "reviewcolumn" -> current.copy(workflow = current.workflow.copy(reviewColumn = value))
                else ->
                    throw IllegalArgumentException(
                        "Unknown config key: '$key'. Valid keys: provider, repo, defaultBaseBranch, " +
                            "branchPrefix, token, verify, workflow.mergeMethod, workflow.deleteBranchOnMerge, " +
                            "workflow.requestColumn, workflow.doneColumn, workflow.reviewColumn",
                    )
            }

        val targetFile = if (global) AiKanbanConfigLoader.saveGlobal(updated) else AiKanbanConfigLoader.save(updated, workingDir)

        if (isJson) {
            println(JsonRenderer.render(updated))
        } else {
            val t = cliContext.terminal
            val scope = if (global) "global configuration" else "configuration"
            t.println(bold(green("✓ Set $key = $value in $scope (${targetFile.absolutePath})")))
        }
    }
}
