package aikanban.cli

import aikanban.cli.prompt.TestInteractivePrompter
import aikanban.config.AiKanbanConfigLoader
import aikanban.config.ConfigPromptTest
import aikanban.provider.ProviderFactory
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import kotlinx.serialization.json.Json
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowAndSyncPromptTest {
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
        dbFile = tempDir.resolve("prompt_integration_test.db").toFile()
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
    @DisplayName("Should prompt for provider and save config during workflow start-issue when config is missing")
    fun testWorkflowStartIssuePromptsWhenMissingConfig() {
        assertFalse(File(tempDir.toFile(), ".aikanban.json").exists())

        prompter.choiceResponses.add("local-git")
        prompter.promptResponses.addAll(listOf("main", "feature/"))
        prompter.confirmResponses.add(true)

        val result = execute("workflow", "start-issue", "Interactive Task", "-b", "feature/interactive-task")
        assertEquals(0, result.exitCode)
        assertTrue(File(tempDir.toFile(), ".aikanban.json").exists())
        assertEquals("local-git", AiKanbanConfigLoader.load(tempDir.toFile()).provider)
    }

    @Test
    @DisplayName("Should bypass interactive prompt when executing in JSON mode even if config is missing")
    fun testWorkflowStartIssueJsonBypassesPrompt() {
        assertFalse(File(tempDir.toFile(), ".aikanban.json").exists())

        val result = execute("workflow", "start-issue", "JSON Task", "--json")
        assertEquals(0, result.exitCode)
        assertTrue(prompter.recordedChoices.isEmpty())
    }

    @Test
    @DisplayName("Should prompt for provider and save config during sync when config is missing")
    fun testSyncCommandPromptsWhenMissingConfig() {
        assertFalse(File(tempDir.toFile(), ".aikanban.json").exists())

        prompter.choiceResponses.add("local-git")
        prompter.promptResponses.addAll(listOf("main", "feature/"))
        prompter.confirmResponses.add(true)

        val result = execute("sync", "--dry-run")
        assertEquals(0, result.exitCode)
        assertTrue(File(tempDir.toFile(), ".aikanban.json").exists())
    }
}
