package aikanban.cli.setup

import aikanban.cli.CliContext
import aikanban.cli.prompt.TestInteractivePrompter
import aikanban.config.AiKanbanConfigLoader
import aikanban.provider.GitCommandRunner
import aikanban.provider.GitProcessResult
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliSetupWizardTest {
    @TempDir
    lateinit var tempDir: Path

    class MockGitRunner(
        var isRepo: Boolean = true,
        var currentBranchName: String = "main",
        var remoteUrls: MutableMap<String, String> = mutableMapOf(),
    ) : GitCommandRunner {
        override fun getCurrentBranch(workingDir: File?): String = currentBranchName

        override fun createAndCheckoutBranch(
            branchName: String,
            baseBranch: String,
            workingDir: File?,
        ): GitProcessResult = GitProcessResult(0, "Created branch $branchName", "")

        override fun pushBranch(
            branchName: String,
            remote: String,
            setUpstream: Boolean,
            workingDir: File?,
        ): GitProcessResult = GitProcessResult(0, "Pushed $branchName", "")

        override fun isGitRepository(workingDir: File?): Boolean = isRepo

        override fun getRemoteUrl(
            remote: String,
            workingDir: File?,
        ): String? = remoteUrls[remote]
    }

    @Test
    @DisplayName("Should correctly probe git config for HTTPS, SSH, and local-git repositories")
    fun testProbeGitConfig() {
        val gitRunnerSsh =
            MockGitRunner(
                remoteUrls = mutableMapOf("origin" to "git@github.com:myorg/my-project.git"),
            )
        val probedSsh = CliSetupWizard.probeGitConfig(tempDir.toFile(), gitRunnerSsh)
        assertEquals("myorg/my-project", probedSsh.detectedRepo)
        assertEquals("github", probedSsh.suggestedProvider)
        assertEquals("git@github.com:myorg/my-project.git", probedSsh.remoteUrl)
        assertTrue(probedSsh.isGitRepository)

        val gitRunnerHttps =
            MockGitRunner(
                remoteUrls = mutableMapOf("origin" to "https://github.com/myorg/https-project"),
            )
        val probedHttps = CliSetupWizard.probeGitConfig(tempDir.toFile(), gitRunnerHttps)
        assertEquals("myorg/https-project", probedHttps.detectedRepo)
        assertEquals("github", probedHttps.suggestedProvider)

        val gitRunnerLocal = MockGitRunner(isRepo = false)
        val probedLocal = CliSetupWizard.probeGitConfig(tempDir.toFile(), gitRunnerLocal)
        assertNull(probedLocal.detectedRepo)
        assertEquals("local-git", probedLocal.suggestedProvider)
        assertFalse(probedLocal.isGitRepository)
    }

    @Test
    @DisplayName("Should return existing config directly without prompting if config file exists")
    fun testEnsureProviderConfigWhenConfigFileExists() {
        val workingDir = tempDir.toFile()
        val configFile = File(workingDir, ".aikanban.json")
        configFile.writeText(
            """
            {
                "provider": "github",
                "repo": "owner/pre-existing",
                "defaultBaseBranch": "main"
            }
            """.trimIndent(),
        )

        val prompter = TestInteractivePrompter(interactive = true)
        val gitRunner = MockGitRunner()

        val config =
            CliSetupWizard.ensureProviderConfig(
                workingDir = workingDir,
                prompter = prompter,
                gitCommandRunner = gitRunner,
            )

        assertEquals("github", config.provider)
        assertEquals("owner/pre-existing", config.repo)
        assertTrue(prompter.recordedPrompts.isEmpty())
        assertTrue(prompter.recordedChoices.isEmpty())
    }

    @Test
    @DisplayName("Should return default config without blocking when prompter is non-interactive")
    fun testEnsureProviderConfigNonInteractive() {
        val workingDir = tempDir.toFile()
        val prompter = TestInteractivePrompter(interactive = false)
        val gitRunner = MockGitRunner()

        val config =
            CliSetupWizard.ensureProviderConfig(
                workingDir = workingDir,
                prompter = prompter,
                gitCommandRunner = gitRunner,
            )

        assertEquals("local-git", config.provider)
        assertEquals("main", config.defaultBaseBranch)
        assertFalse(File(workingDir, ".aikanban.json").exists())
        assertTrue(prompter.recordedPrompts.isEmpty())
    }

    @Test
    @DisplayName("Should prompt and save GitHub configuration when user selects GitHub interactively")
    fun testEnsureProviderConfigInteractiveGitHub() {
        val workingDir = tempDir.toFile()
        val gitRunner =
            MockGitRunner(
                remoteUrls = mutableMapOf("origin" to "git@github.com:0oWoodenDooro0/AiKanban.git"),
                currentBranchName = "feature/new-feature",
            )
        val prompter =
            TestInteractivePrompter(
                interactive = true,
                choiceResponses = mutableListOf("github"),
                promptResponses =
                    mutableListOf(
                        "0oWoodenDooro0/AiKanban",
                        "main",
                        "feature/",
                    ),
                confirmResponses = mutableListOf(true),
            )

        val config =
            CliSetupWizard.ensureProviderConfig(
                workingDir = workingDir,
                prompter = prompter,
                gitCommandRunner = gitRunner,
            )

        assertEquals("github", config.provider)
        assertEquals("0oWoodenDooro0/AiKanban", config.repo)
        assertEquals("main", config.defaultBaseBranch)
        assertEquals("feature/", config.branchPrefix)

        val savedConfigFile = File(workingDir, ".aikanban.json")
        assertTrue(savedConfigFile.exists())
        val savedConfig = AiKanbanConfigLoader.load(workingDir)
        assertEquals("github", savedConfig.provider)
        assertEquals("0oWoodenDooro0/AiKanban", savedConfig.repo)
    }

    @Test
    @DisplayName("Should prompt and save local-git configuration when user selects local-git")
    fun testEnsureProviderConfigInteractiveLocalGit() {
        val workingDir = tempDir.toFile()
        val gitRunner = MockGitRunner()
        val prompter =
            TestInteractivePrompter(
                interactive = true,
                choiceResponses = mutableListOf("local-git"),
                promptResponses =
                    mutableListOf(
                        "master",
                        "task/",
                    ),
                confirmResponses = mutableListOf(true),
            )

        val config =
            CliSetupWizard.ensureProviderConfig(
                workingDir = workingDir,
                prompter = prompter,
                gitCommandRunner = gitRunner,
            )

        assertEquals("local-git", config.provider)
        assertEquals("master", config.defaultBaseBranch)
        assertEquals("task/", config.branchPrefix)

        val savedConfigFile = File(workingDir, ".aikanban.json")
        assertTrue(savedConfigFile.exists())
        val savedConfig = AiKanbanConfigLoader.load(workingDir)
        assertEquals("local-git", savedConfig.provider)
        assertEquals("master", savedConfig.defaultBaseBranch)
    }

    @Test
    @DisplayName("Should return in-memory config without writing file if user declines to save")
    fun testEnsureProviderConfigDeclineSave() {
        val workingDir = tempDir.toFile()
        val gitRunner = MockGitRunner()
        val prompter =
            TestInteractivePrompter(
                interactive = true,
                choiceResponses = mutableListOf("local-git"),
                promptResponses = mutableListOf("main", "feature/"),
                confirmResponses = mutableListOf(false),
            )

        val config =
            CliSetupWizard.ensureProviderConfig(
                workingDir = workingDir,
                prompter = prompter,
                gitCommandRunner = gitRunner,
            )

        assertEquals("local-git", config.provider)
        assertFalse(File(workingDir, ".aikanban.json").exists())
    }

    @Test
    @DisplayName("Should bypass wizard in ensureConfig helper when provider is explicitly overridden")
    fun testEnsureConfigBypassOnOverrideProvider() {
        val workingDir = tempDir.toFile()
        val prompter = TestInteractivePrompter(interactive = true)
        val context =
            CliContext(
                service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${File(workingDir, "test.db").absolutePath}")),
                workingDir = workingDir,
                prompter = prompter,
            )

        val result = context.ensureConfig(overrideProvider = "local-git")
        assertEquals(context.config, result)
        assertTrue(prompter.recordedChoices.isEmpty())
    }

    @Test
    @DisplayName("Should bypass wizard in ensureConfig helper when output is JSON mode")
    fun testEnsureConfigBypassOnJsonMode() {
        val workingDir = tempDir.toFile()
        val prompter = TestInteractivePrompter(interactive = true)
        val context =
            CliContext(
                service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${File(workingDir, "test.db").absolutePath}")),
                workingDir = workingDir,
                prompter = prompter,
            )

        val result = context.ensureConfig(isJson = true)
        assertEquals(context.config, result)
        assertTrue(prompter.recordedChoices.isEmpty())
    }

    @Test
    @DisplayName("Should bypass wizard in ensureConfig helper when explicit target is specified")
    fun testEnsureConfigBypassOnExplicitTarget() {
        val workingDir = tempDir.toFile()
        val prompter = TestInteractivePrompter(interactive = true)
        val context =
            CliContext(
                service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${File(workingDir, "test.db").absolutePath}")),
                workingDir = workingDir,
                prompter = prompter,
            )

        val result = context.ensureConfig(bypassIfTargetSpecified = true)
        assertEquals(context.config, result)
        assertTrue(prompter.recordedChoices.isEmpty())
    }

    @Test
    @DisplayName("Should trigger wizard in ensureConfig helper when no config file exists and no bypass conditions met")
    fun testEnsureConfigTriggersWizard() {
        val workingDir = tempDir.toFile()
        val prompter =
            TestInteractivePrompter(
                interactive = true,
                choiceResponses = mutableListOf("local-git"),
                promptResponses = mutableListOf("main", "feature/"),
                confirmResponses = mutableListOf(true),
            )
        val context =
            CliContext(
                service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${File(workingDir, "test.db").absolutePath}")),
                workingDir = workingDir,
                prompter = prompter,
            )

        val result = context.ensureConfig()
        assertEquals("local-git", result.provider)
        assertTrue(File(workingDir, ".aikanban.json").exists())
    }
}
