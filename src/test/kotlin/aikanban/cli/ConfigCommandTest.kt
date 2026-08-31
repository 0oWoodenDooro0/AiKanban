package aikanban.cli

import aikanban.cli.prompt.TestInteractivePrompter
import aikanban.config.AiKanbanConfigLoader
import aikanban.config.ConfigPromptTest
import aikanban.provider.ProviderFactory
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigCommandTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var service: KanbanService
    private lateinit var fakeGitRunner: ConfigPromptTest.MockGitRunner
    private lateinit var providerFactory: ProviderFactory
    private lateinit var prompter: TestInteractivePrompter
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("config_cmd_test.db").toFile()
        service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}"))
        fakeGitRunner = ConfigPromptTest.MockGitRunner()
        prompter = TestInteractivePrompter(interactive = true)
        providerFactory =
            ProviderFactory(
                kanbanService = service,
                gitCommandRunner = fakeGitRunner,
                workingDir = tempDir.toFile(),
            )
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    private data class CliExecutionResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun execute(vararg args: String): CliExecutionResult {
        val outStream = ByteArrayOutputStream()
        val errStream = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        val printOut = PrintStream(outStream, true, StandardCharsets.UTF_8)
        val printErr = PrintStream(errStream, true, StandardCharsets.UTF_8)

        System.setOut(printOut)
        System.setErr(printErr)

        try {
            val command =
                AiKanbanCommand(
                    serviceOverride = service,
                    providerFactoryOverride = providerFactory,
                    prompterOverride = prompter,
                    workingDirOverride = tempDir.toFile(),
                )
            val exitCode = command.parseArgs(args.toList())
            return CliExecutionResult(
                exitCode = exitCode,
                stdout = outStream.toString(StandardCharsets.UTF_8).trim(),
                stderr = errStream.toString(StandardCharsets.UTF_8).trim(),
            )
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    @Test
    @DisplayName("Should display current config in human-readable format")
    fun testConfigShowHuman() {
        val configFile = File(tempDir.toFile(), ".aikanban.json")
        configFile.writeText(
            """
            {
                "provider": "github",
                "repo": "owner/repo",
                "defaultBaseBranch": "main",
                "branchPrefix": "feature/"
            }
            """.trimIndent(),
        )

        val result = execute("config", "show")
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("github"))
        assertTrue(result.stdout.contains("owner/repo"))
        assertTrue(result.stdout.contains("main"))
    }

    @Test
    @DisplayName("Should display current config in JSON format")
    fun testConfigShowJson() {
        val configFile = File(tempDir.toFile(), ".aikanban.json")
        configFile.writeText(
            """
            {
                "provider": "github",
                "repo": "owner/repo",
                "defaultBaseBranch": "develop",
                "branchPrefix": "feature/"
            }
            """.trimIndent(),
        )

        val result = execute("config", "show", "--json")
        assertEquals(0, result.exitCode)
        val parsed = json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("github", parsed["provider"]?.jsonPrimitive?.content)
        assertEquals("owner/repo", parsed["repo"]?.jsonPrimitive?.content)
        assertEquals("develop", parsed["defaultBaseBranch"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("Should initialize config non-interactively when flags are provided")
    fun testConfigInitWithFlags() {
        val result =
            execute(
                "config",
                "init",
                "--provider",
                "github",
                "--repo",
                "myorg/cli-tool",
                "--base",
                "master",
                "--prefix",
                "feat/",
                "--force",
            )
        assertEquals(0, result.exitCode)

        val savedConfig = AiKanbanConfigLoader.load(tempDir.toFile())
        assertEquals("github", savedConfig.provider)
        assertEquals("myorg/cli-tool", savedConfig.repo)
        assertEquals("master", savedConfig.defaultBaseBranch)
        assertEquals("feat/", savedConfig.branchPrefix)
    }

    @Test
    @DisplayName("Should initialize config interactively when init command is run without flags")
    fun testConfigInitInteractive() {
        prompter.choiceResponses.add("github")
        prompter.promptResponses.addAll(listOf("interactive/repo", "main", "feature/"))
        prompter.confirmResponses.add(true)

        val result = execute("config", "init", "--force")
        assertEquals(0, result.exitCode)

        val savedConfig = AiKanbanConfigLoader.load(tempDir.toFile())
        assertEquals("github", savedConfig.provider)
        assertEquals("interactive/repo", savedConfig.repo)
    }

    @Test
    @DisplayName("Should update specific property using config set command")
    fun testConfigSetProperty() {
        val configFile = File(tempDir.toFile(), ".aikanban.json")
        configFile.writeText(
            """
            {
                "provider": "local-git",
                "defaultBaseBranch": "main"
            }
            """.trimIndent(),
        )

        val result1 = execute("config", "set", "provider", "github")
        assertEquals(0, result1.exitCode)

        val result2 = execute("config", "set", "repo", "neworg/newrepo")
        assertEquals(0, result2.exitCode)

        val savedConfig = AiKanbanConfigLoader.load(tempDir.toFile())
        assertEquals("github", savedConfig.provider)
        assertEquals("neworg/newrepo", savedConfig.repo)
    }

    @Test
    @DisplayName("Should display and update global config using --global flag")
    fun testGlobalConfigCommands() {
        val globalDir = tempDir.resolve("global_config_dir").toFile().apply { mkdirs() }
        val globalFile = File(globalDir, "config.json")
        AiKanbanConfigLoader.overrideGlobalConfigFile = globalFile

        try {
            // 1. Init global config
            val initResult =
                execute(
                    "config",
                    "init",
                    "--global",
                    "--provider",
                    "github",
                    "--token",
                    "gh-token-123",
                    "--base",
                    "master",
                )
            assertEquals(0, initResult.exitCode)
            assertTrue(globalFile.exists())

            // 2. Show global config
            val showResult = execute("config", "show", "--global", "--json")
            assertEquals(0, showResult.exitCode)
            val parsedGlobal = json.parseToJsonElement(showResult.stdout).jsonObject
            assertEquals("github", parsedGlobal["provider"]?.jsonPrimitive?.content)
            assertEquals("master", parsedGlobal["defaultBaseBranch"]?.jsonPrimitive?.content)
            assertEquals(true, parsedGlobal["tokenConfigured"]?.jsonPrimitive?.content?.toBoolean())
            assertEquals(true, parsedGlobal["isGlobal"]?.jsonPrimitive?.content?.toBoolean())

            // 3. Set global config property
            val setResult = execute("config", "set", "branchPrefix", "global-feat/", "--global", "--json")
            assertEquals(0, setResult.exitCode)

            val reloadedGlobal = AiKanbanConfigLoader.loadGlobal()
            assertEquals("global-feat/", reloadedGlobal.branchPrefix)
        } finally {
            AiKanbanConfigLoader.overrideGlobalConfigFile = null
        }
    }
}
