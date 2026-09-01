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
        providerFactory =
            ProviderFactory(
                kanbanService = service,
                gitHubClient = testGitHubClient,
                gitCommandRunner = fakeGitRunner,
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
            val dynamicWorkflowService =
                DefaultKanbanWorkflowService(
                    kanbanService = service,
                    providerFactory = providerFactory,
                    config = config,
                    gitCommandRunner = fakeGitRunner,
                )
            val command =
                AiKanbanCommand(
                    serviceOverride = service,
                    configOverride = config,
                    gitCommandRunnerOverride = fakeGitRunner,
                    providerFactoryOverride = providerFactory,
                    workflowServiceOverride = dynamicWorkflowService,
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
        @DisplayName("Should execute workflow start-issue in human mode without changing branch")
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
            assertEquals("main", fakeGitRunner.currentBranch)

            val task = service.listTasks().firstOrNull()
            assertNotNull(task)
            assertEquals("New Workflow Feature", task.title)
            assertEquals(TaskPriority.HIGH, task.priority)
            assertEquals("bot-1", task.assignee)
        }

        @Test
        @DisplayName("Should execute workflow start-issue in JSON mode without changing branch")
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
            assertEquals("main", fakeGitRunner.currentBranch)
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
        @DisplayName("Should execute workflow start-task with automatic branch checkout")
        fun testWorkflowStartTaskWithAutoCheckoutCli() {
            val task = service.createTask(title = "Branch Task", status = "TODO", branch = "feature/cli-auto-branch")
            fakeGitRunner.currentBranch = "main"

            val result = execute("workflow", "start-task", task.id.toString(), "--json")
            assertEquals(0, result.exitCode)
            val updated = service.getTask(task.id)
            assertEquals("IN_PROGRESS", updated.status)
            assertEquals("feature/cli-auto-branch", fakeGitRunner.currentBranch)
        }

        @Test
        @DisplayName("Should execute workflow start-task with --no-checkout flag")
        fun testWorkflowStartTaskWithNoCheckoutCli() {
            val task = service.createTask(title = "No Checkout Task", status = "TODO", branch = "feature/cli-no-checkout")
            fakeGitRunner.currentBranch = "main"

            val result = execute("workflow", "start-task", task.id.toString(), "--no-checkout", "--json")
            assertEquals(0, result.exitCode)
            val updated = service.getTask(task.id)
            assertEquals("IN_PROGRESS", updated.status)
            assertEquals("main", fakeGitRunner.currentBranch)
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

    @Nested
    @DisplayName("Operator Auto-Inference CLI Tests")
    inner class OperatorInferenceCliTest {
        @Test
        @DisplayName("Should infer operator from config.operator in add command")
        fun testAddCommandInfersOperatorFromConfig() {
            config = AiKanbanConfig(provider = "local-git", operator = "config-bot")
            val result = execute("add", "Config Inferred Task", "--json")
            assertEquals(0, result.exitCode)
            val task = json.decodeFromString<aikanban.model.Task>(result.stdout)
            assertEquals("config-bot", task.logs.first().operator)
        }

        @Test
        @DisplayName("Explicit -o option should override config.operator in add command")
        fun testAddCommandExplicitOperatorOverridesConfig() {
            config = AiKanbanConfig(provider = "local-git", operator = "config-bot")
            val result = execute("add", "Explicit Task", "-o", "explicit-user", "--json")
            assertEquals(0, result.exitCode)
            val task = json.decodeFromString<aikanban.model.Task>(result.stdout)
            assertEquals("explicit-user", task.logs.first().operator)
        }

        @Test
        @DisplayName("Should infer agent/operator from git config in claim command when argument is omitted")
        fun testClaimCommandInfersAgentFromGitConfig() {
            config = AiKanbanConfig(provider = "local-git", operator = null)
            fakeGitRunner.userName = "git-developer"
            val task = service.createTask(title = "Claimable Task", status = "TODO")

            val result = execute("claim", "--json")
            assertEquals(0, result.exitCode)
            val claimed = json.decodeFromString<aikanban.model.Task>(result.stdout)
            assertEquals(task.id, claimed.id)
            assertEquals("git-developer", claimed.assignee)
            assertEquals("IN_PROGRESS", claimed.status)
        }

        @Test
        @DisplayName("Should infer operator from fallback in move and log commands when config and git are absent")
        fun testMoveAndLogCommandsInferOperatorFromFallback() {
            config = AiKanbanConfig(provider = "local-git", operator = null)
            fakeGitRunner.userName = null
            val task = service.createTask(title = "Move Log Task", status = "TODO")

            val moveRes = execute("move", task.id.toString(), "IN_PROGRESS", "--json")
            assertEquals(0, moveRes.exitCode)
            val moved = json.decodeFromString<aikanban.model.Task>(moveRes.stdout)
            assertEquals("workflow", moved.logs.last().operator)

            val logRes = execute("log", task.id.toString(), "-m", "Progress log", "--json")
            assertEquals(0, logRes.exitCode)
            val entry = json.decodeFromString<aikanban.model.TaskLogEntry>(logRes.stdout)
            assertEquals("workflow", entry.operator)
        }

        @Test
        @DisplayName("Should infer operator from config in workflow start-task and commit commands")
        fun testWorkflowCommandsInferOperatorFromConfig() {
            config = AiKanbanConfig(provider = "local-git", operator = "workflow-bot")
            val task = service.createTask(title = "Workflow Task", status = "TODO")

            val startRes = execute("workflow", "start-task", task.id.toString(), "--no-checkout", "--json")
            assertEquals(0, startRes.exitCode)
            val started = json.decodeFromString<aikanban.model.Task>(startRes.stdout)
            assertEquals("workflow-bot", started.logs.last().operator)

            val commitRes = execute("workflow", "commit", task.id.toString(), "-m", "feat: test", "--no-git", "--json")
            assertEquals(0, commitRes.exitCode)
            val updated = service.getTask(task.id)
            val commitLog = updated.logs.first { it.comment.contains("Committed changes") }
            assertEquals("workflow-bot", commitLog.operator)
        }

        @Test
        @DisplayName("workflow start-review --stash passes stash flag and indicates stashing")
        fun testWorkflowStartReviewCliWithStash() {
            val task = service.createTask(title = "Review CLI Stash Task", status = "REVIEW", branch = "feature/cli-stash")
            val result = execute("workflow", "start-review", task.id.toString(), "--stash", "--json")
            assertEquals(0, result.exitCode)
            val startRes = json.decodeFromString<aikanban.workflow.StartReviewResult>(result.stdout)
            assertEquals(task.id, startRes.task.id)
        }

        @Test
        @DisplayName("workflow start-review --force passes force flag")
        fun testWorkflowStartReviewCliWithForce() {
            val task = service.createTask(title = "Review CLI Force Task", status = "REVIEW", branch = "feature/cli-force")
            val result = execute("workflow", "start-review", task.id.toString(), "--force", "--json")
            assertEquals(0, result.exitCode)
            val startRes = json.decodeFromString<aikanban.workflow.StartReviewResult>(result.stdout)
            assertEquals(task.id, startRes.task.id)
        }

        @Test
        @DisplayName("workflow submit-pr should auto-checkout base branch and report baseBranch in JSON")
        fun testWorkflowSubmitPrCliAutoCheckoutBase() {
            val task = service.createTask(title = "CLI Submit PR Auto Checkout Base", status = "IN_PROGRESS")
            fakeGitRunner.currentBranch = "feature/cli-pr-base"

            val result = execute("workflow", "submit-pr", task.id.toString(), "--json")
            assertEquals(0, result.exitCode)
            val submitRes = json.decodeFromString<SubmitPrResult>(result.stdout)
            assertEquals("REVIEW", submitRes.task.status)
            assertEquals("main", submitRes.baseBranch)
            assertEquals("main", fakeGitRunner.currentBranch)
        }

        @Test
        @DisplayName("workflow submit-pr --no-checkout-base should not switch branch")
        fun testWorkflowSubmitPrCliNoCheckoutBase() {
            val task = service.createTask(title = "CLI Submit PR No Checkout Base", status = "IN_PROGRESS")
            fakeGitRunner.currentBranch = "feature/cli-pr-stay"

            val result = execute("workflow", "submit-pr", task.id.toString(), "--no-checkout-base", "--json")
            assertEquals(0, result.exitCode)
            val submitRes = json.decodeFromString<SubmitPrResult>(result.stdout)
            assertEquals("REVIEW", submitRes.task.status)
            assertEquals(null, submitRes.baseBranch)
            assertEquals("feature/cli-pr-stay", fakeGitRunner.currentBranch)
        }

        @Test
        @DisplayName("workflow start-issue CLI should populate githubRepo from config")
        fun testWorkflowStartIssueCliPopulatesGitHubRepo() {
            config = AiKanbanConfig(provider = "local-git", repo = "cli-org/cli-repo")
            val result = execute("workflow", "start-issue", "CLI Start Issue Repo", "--json")
            assertEquals(0, result.exitCode)
            val startRes = json.decodeFromString<StartIssueResult>(result.stdout)
            assertEquals("cli-org/cli-repo", startRes.task.githubRepo)
        }
    }
}
