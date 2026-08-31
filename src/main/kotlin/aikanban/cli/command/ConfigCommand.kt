package aikanban.cli.command

import aikanban.cli.CliContext
import aikanban.cli.renderer.JsonRenderer
import aikanban.config.AiKanbanConfig
import aikanban.config.AiKanbanConfigLoader
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
    val provider: String,
    val defaultBaseBranch: String,
    val repo: String?,
    val branchPrefix: String,
    val tokenConfigured: Boolean,
)

class ConfigCommand : CliktCommand(name = "config") {
    override fun help(context: Context): String = "Manage AiKanban VCS provider and project configuration"

    private val cliContext by requireObject<CliContext>()
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
            val isJson = json || cliContext.jsonOutput
            val workingDir = cliContext.workingDir
            val configFile = AiKanbanConfigLoader.findConfigFile(workingDir)
            val config = AiKanbanConfigLoader.load(workingDir)

            if (isJson) {
                val result =
                    ConfigShowResult(
                        configFile = configFile?.absolutePath,
                        provider = config.provider,
                        defaultBaseBranch = config.defaultBaseBranch,
                        repo = config.repo,
                        branchPrefix = config.branchPrefix,
                        tokenConfigured = !config.token.isNullOrBlank(),
                    )
                println(JsonRenderer.render(result))
            } else {
                val t = cliContext.terminal
                t.println(bold(cyan("AiKanban Configuration")))
                val fileDisplay = if (configFile != null) green(configFile.absolutePath) else yellow("(none, using defaults)")
                t.println("  • Config file: $fileDisplay")
                t.println("  • Provider: ${blue(config.provider)}")
                t.println("  • Default base branch: ${config.defaultBaseBranch}")
                t.println("  • Repository: ${config.repo ?: "(not set)"}")
                t.println("  • Branch prefix: ${config.branchPrefix}")
                t.println("  • Token configured: ${if (!config.token.isNullOrBlank()) "yes" else "no"}")
            }
        }
    }
}

class ConfigShowCommand : CliktCommand(name = "show") {
    override fun help(context: Context): String = "Display current active configuration and configuration file location"

    private val cliContext by requireObject<CliContext>()
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val workingDir = cliContext.workingDir
        val configFile = AiKanbanConfigLoader.findConfigFile(workingDir)
        val config = AiKanbanConfigLoader.load(workingDir)

        if (isJson) {
            val result =
                ConfigShowResult(
                    configFile = configFile?.absolutePath,
                    provider = config.provider,
                    defaultBaseBranch = config.defaultBaseBranch,
                    repo = config.repo,
                    branchPrefix = config.branchPrefix,
                    tokenConfigured = !config.token.isNullOrBlank(),
                )
            println(JsonRenderer.render(result))
        } else {
            val t = cliContext.terminal
            t.println(bold(cyan("AiKanban Configuration")))
            val fileDisplay = if (configFile != null) green(configFile.absolutePath) else yellow("(none, using defaults)")
            t.println("  • Config file: $fileDisplay")
            t.println("  • Provider: ${blue(config.provider)}")
            t.println("  • Default base branch: ${config.defaultBaseBranch}")
            t.println("  • Repository: ${config.repo ?: "(not set)"}")
            t.println("  • Branch prefix: ${config.branchPrefix}")
            t.println("  • Token configured: ${if (!config.token.isNullOrBlank()) "yes" else "no"}")
        }
    }
}

class ConfigInitCommand : CliktCommand(name = "init") {
    override fun help(context: Context): String = "Initialize or reconfigure .aikanban.json"

    private val cliContext by requireObject<CliContext>()

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
        val configFile = File(workingDir, ".aikanban.json")

        val hasDirectFlags = provider != null || repo != null || base != null || prefix != null || token != null

        val config =
            if (hasDirectFlags) {
                val current = if (configFile.exists() && !force) AiKanbanConfigLoader.load(workingDir) else AiKanbanConfig()
                val updated =
                    current.copy(
                        provider = provider ?: current.provider,
                        repo = repo ?: current.repo,
                        defaultBaseBranch = base ?: current.defaultBaseBranch,
                        branchPrefix = prefix ?: current.branchPrefix,
                        token = token ?: current.token,
                    )
                AiKanbanConfigLoader.save(updated, workingDir)
                updated
            } else {
                AiKanbanConfigLoader.ensureProviderConfig(
                    workingDir = workingDir,
                    prompter = cliContext.prompter,
                    gitCommandRunner = cliContext.gitCommandRunner,
                    forcePrompt = force,
                )
            }

        if (isJson) {
            println(JsonRenderer.render(config))
        } else {
            val t = cliContext.terminal
            t.println(bold(green("✓ Initialized configuration in ${File(workingDir, ".aikanban.json").absolutePath}")))
            t.println("  • Provider: ${blue(config.provider)}")
            if (config.repo != null) {
                t.println("  • Repo: ${config.repo}")
            }
            t.println("  • Base branch: ${config.defaultBaseBranch}")
        }
    }
}

class ConfigSetCommand : CliktCommand(name = "set") {
    override fun help(context: Context): String = "Set a configuration property in .aikanban.json"

    private val cliContext by requireObject<CliContext>()

    private val key by argument("key", help = "Configuration key (provider, repo, defaultBaseBranch/base, branchPrefix/prefix, token)")
    private val value by argument("value", help = "Configuration value")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val workingDir = cliContext.workingDir
        val current = AiKanbanConfigLoader.load(workingDir)

        val updated =
            when (key.lowercase()) {
                "provider" -> current.copy(provider = value)
                "repo", "repository" -> current.copy(repo = value.takeIf { it.isNotBlank() })
                "defaultbasebranch", "base", "branch" -> current.copy(defaultBaseBranch = value)
                "branchprefix", "prefix" -> current.copy(branchPrefix = value)
                "token" -> current.copy(token = value.takeIf { it.isNotBlank() })
                else ->
                    throw IllegalArgumentException(
                        "Unknown config key: '$key'. Valid keys: provider, repo, defaultBaseBranch, branchPrefix, token",
                    )
            }

        val targetFile = AiKanbanConfigLoader.save(updated, workingDir)

        if (isJson) {
            println(JsonRenderer.render(updated))
        } else {
            val t = cliContext.terminal
            t.println(bold(green("✓ Set $key = $value in ${targetFile.absolutePath}")))
        }
    }
}
