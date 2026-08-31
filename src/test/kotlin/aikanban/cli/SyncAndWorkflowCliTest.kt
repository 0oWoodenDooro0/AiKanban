package aikanban.cli

import aikanban.config.AiKanbanConfig
import aikanban.model.TaskPriority
import aikanban.provider.LocalGitProviderTest
import aikanban.provider.ProviderFactory
import aikanban.provider.ProviderSyncResult
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import aikanban.workflow.DefaultKanbanWorkflowService
import aikanban.workflow.StartIssueResult
import aikanban.workflow.SubmitPrResult
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncAndWorkflowCliTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var service: KanbanService
    private lateinit var fakeGitRunner: LocalGitProviderTest.FakeGitCommandRunner
    private lateinit var providerFactory: ProviderFactory
    private lateinit var workflowService: DefaultKanbanWorkflowService
    private lateinit var config: AiKanbanConfig
    private lateinit var testGitHubClient: TestGitHubClient
    private lateinit var gitHubSyncService: aikanban.github.service.GitHubSyncService
    private val json = Json { ignoreUnknownKeys = true }

    private class TestGitHubClient : aikanban.github.client.GitHubClient {
        val issues = mutableListOf<aikanban.github.model.GitHubIssueDto>()

        override suspend fun fetchRepositoryIssues(
            owner: String,
            repo: String,
            state: String,
            labels: Set<String>,
            token: String?,
            page: Int,
            perPage: Int,
        ): List<aikanban.github.model.GitHubIssueDto> = issues

        override suspend fun fetchIssue(
            owner: String,
            repo: String,
            number: Int,
            token: String?,
        ): aikanban.github.model.GitHubIssueDto? = issues.find { it.number == number }

        override fun close() {}
    }

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("sync_workflow_cli_test.db").toFile()
        service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}"))
        fakeGitRunner = LocalGitProviderTest.FakeGitCommandRunner()
        config = AiKanbanConfig(provider = "local-git", defaultBaseBranch = "main")
        testGitHubClient = TestGitHubClient()
        gitHubSyncService = aikanban.github.service.DefaultGitHubSyncService(service, testGitHubClient)
        providerFactory =
            ProviderFactory(
                kanbanService = service,
                gitCommandRunner = fakeGitRunner,
                gitHubSyncService = gitHubSyncService,
                workingDir = tempDir.toFile(),
            )
        workflowService =
            DefaultKanbanWorkflowService(
                kanbanService = service,
                providerFactory = providerFactory,
                config = config,
                gitCommandRunner = fakeGitRunner,
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
                    configOverride = config,
                    providerFactoryOverride = providerFactory,
                    workflowServiceOverride = workflowService,
                    gitHubSyncServiceOverride = gitHubSyncService,
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

    @Nested
    @DisplayName("Sync Command Tests")
    inner class SyncCommandTests {
        @Test
        @DisplayName("Should execute neutral sync command in human mode")
        fun testSyncHuman() {
            val result = execute("sync", "--dry-run")
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Synced") || result.stdout.contains("local-git") || result.stdout.contains("DRY RUN"))
        }

        @Test
        @DisplayName("Should execute neutral sync command in JSON mode")
        fun testSyncJson() {
            val result = execute("sync", "--dry-run", "--json")
            assertEquals(0, result.exitCode)
            val syncResult = json.decodeFromString<ProviderSyncResult>(result.stdout)
            assertEquals("local-git", syncResult.provider)
        }

        @Test
        @DisplayName("Should execute sync command with github provider in dry-run mode")
        fun testSyncGitHubCli() {
            val result = execute("sync", "owner/repo", "--provider", "github", "--dry-run", "--json")
            assertEquals(0, result.exitCode)
            val syncResult = json.decodeFromString<ProviderSyncResult>(result.stdout)
            assertEquals("github", syncResult.provider)
            assertEquals("owner/repo", syncResult.repo)
        }
    }

    @Nested
    @DisplayName("Workflow Command Tests")
    inner class WorkflowCommandTests {
        @Test
        @DisplayName("Should execute workflow start-issue in human mode")
        fun testStartIssueHuman() {
            val result =
                execute(
                    "workflow",
                    "start-issue",
                    "New Workflow Feature",
                    "-d",
                    "Feature Description",
                    "-p",
                    "HIGH",
                    "-t",
                    "cli,workflow",
                    "-b",
                    "feature/cli-workflow",
                    "-a",
                    "bot-1",
                )
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("New Workflow Feature"))
            assertTrue(result.stdout.contains("feature/cli-workflow"))

            val task = service.listTasks().firstOrNull()
            assertNotNull(task)
            assertEquals("New Workflow Feature", task.title)
            assertEquals(TaskPriority.HIGH, task.priority)
            assertEquals("bot-1", task.assignee)
        }

        @Test
        @DisplayName("Should execute workflow start-issue in JSON mode")
        fun testStartIssueJson() {
            val result =
                execute(
                    "workflow",
                    "start-issue",
                    "JSON Workflow Task",
                    "--json",
                )
            assertEquals(0, result.exitCode)
            val startResult = json.decodeFromString<StartIssueResult>(result.stdout)
            assertEquals("JSON Workflow Task", startResult.task.title)
            assertNotNull(startResult.branch)
            assertNotNull(startResult.issue)
        }

        @Test
        @DisplayName("Should execute workflow submit-pr in human and JSON modes")
        fun testSubmitPrCli() {
            val task = service.createTask(title = "Task To Submit PR", status = "IN_PROGRESS")
            fakeGitRunner.currentBranch = "feature/task-pr"

            val result =
                execute(
                    "workflow",
                    "submit-pr",
                    task.id.toString(),
                    "--title",
                    "feat: ready for review",
                    "--json",
                )
            assertEquals(0, result.exitCode)
            val submitResult = json.decodeFromString<SubmitPrResult>(result.stdout)
            assertEquals("REVIEW", submitResult.task.status)
            assertNotNull(submitResult.pr.url)
        }

        @Test
        @DisplayName("Should execute workflow verify in CLI")
        fun testWorkflowVerifyCli() {
            val result = execute("workflow", "verify", "--json")
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("success"))
        }

        @Test
        @DisplayName("Should execute workflow start-task in CLI")
        fun testWorkflowStartTaskCli() {
            val task = service.createTask(title = "Pending Task", status = "TODO")
            val result = execute("workflow", "start-task", task.id.toString(), "-a", "agent-x", "--json")
            assertEquals(0, result.exitCode)
            val updated = service.getTask(task.id)
            assertEquals("IN_PROGRESS", updated.status)
            assertEquals("agent-x", updated.assignee)
        }

        @Test
        @DisplayName("Should execute workflow commit in CLI")
        fun testWorkflowCommitCli() {
            val task = service.createTask(title = "Task In Dev", status = "IN_PROGRESS")
            val result = execute("workflow", "commit", task.id.toString(), "-m", "feat: cli commit", "--no-git", "--json")
            assertEquals(0, result.exitCode)
            val updated = service.getTask(task.id)
            assertTrue(updated.logs.any { it.comment.contains("Committed changes: feat: cli commit") })
        }
    }
}
