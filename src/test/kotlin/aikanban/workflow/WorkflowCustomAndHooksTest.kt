package aikanban.workflow

import aikanban.config.AiKanbanConfig
import aikanban.config.CustomWorkflowDefinition
import aikanban.provider.LocalGitProviderTest
import aikanban.provider.ProviderFactory
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowCustomAndHooksTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var service: KanbanService
    private lateinit var fakeGitRunner: LocalGitProviderTest.FakeGitCommandRunner
    private lateinit var fakeShellRunner: FakeShellCommandRunner
    private lateinit var providerFactory: ProviderFactory

    class FakeShellCommandRunner : ShellCommandRunner {
        val executedCommands = mutableListOf<String>()
        var commandResultFactory: (String) -> CommandExecutionResult = { cmd ->
            CommandExecutionResult(
                command = cmd,
                exitCode = 0,
                stdout = "output of $cmd",
                stderr = "",
                success = true,
            )
        }

        override fun execute(
            command: String,
            workingDir: File,
        ): CommandExecutionResult {
            executedCommands.add(command)
            return commandResultFactory(command)
        }
    }

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("custom_wf_test.db").toFile()
        service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}"))
        fakeGitRunner = LocalGitProviderTest.FakeGitCommandRunner()
        fakeShellRunner = FakeShellCommandRunner()
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

    private fun createWorkflowService(config: AiKanbanConfig): DefaultKanbanWorkflowService {
        return DefaultKanbanWorkflowService(
            kanbanService = service,
            providerFactory = providerFactory,
            config = config,
            gitCommandRunner = fakeGitRunner,
            shellCommandRunner = fakeShellRunner,
        )
    }

    @Test
    @DisplayName("Should run quality verification commands successfully")
    fun testRunVerifySuccess() =
        runBlocking {
            val config =
                AiKanbanConfig(
                    verify = listOf("./gradlew test", "./gradlew ktlintCheck"),
                )
            val workflowService = createWorkflowService(config)

            val result = workflowService.runVerify()
            assertTrue(result.success)
            assertEquals(2, result.executedCommands.size)
            assertEquals(listOf("./gradlew test", "./gradlew ktlintCheck"), fakeShellRunner.executedCommands)
        }

    @Test
    @DisplayName("Should report failure when verify command fails")
    fun testRunVerifyFailure() =
        runBlocking {
            val config =
                AiKanbanConfig(
                    verify = listOf("./gradlew test", "./gradlew ktlintCheck"),
                )
            fakeShellRunner.commandResultFactory = { cmd ->
                if (cmd.contains("ktlintCheck")) {
                    CommandExecutionResult(cmd, exitCode = 1, stdout = "", stderr = "Lint errors found", success = false)
                } else {
                    CommandExecutionResult(cmd, exitCode = 0, stdout = "OK", stderr = "", success = true)
                }
            }
            val workflowService = createWorkflowService(config)

            val result = workflowService.runVerify()
            assertFalse(result.success)
            assertEquals(2, result.executedCommands.size)
            assertTrue(result.message.contains("FAILED") || result.message.contains("ktlintCheck"))
        }

    @Test
    @DisplayName("Should return clean notice when no verify commands are configured")
    fun testRunVerifyNoCommands() =
        runBlocking {
            val config = AiKanbanConfig(verify = emptyList())
            val workflowService = createWorkflowService(config)

            val result = workflowService.runVerify()
            assertTrue(result.success)
            assertEquals(0, result.executedCommands.size)
        }

    @Test
    @DisplayName("Should execute custom workflow steps in order")
    fun testRunCustomWorkflow() =
        runBlocking {
            val config =
                AiKanbanConfig(
                    workflows =
                        mapOf(
                            "ci-build" to
                                CustomWorkflowDefinition(
                                    description = "CI build step",
                                    steps = listOf("./gradlew compileKotlin", "./gradlew test"),
                                ),
                        ),
                )
            val workflowService = createWorkflowService(config)

            val result = workflowService.runWorkflow("ci-build")
            assertTrue(result.success)
            assertEquals("ci-build", result.workflowName)
            assertEquals(2, result.executedSteps.size)
            assertEquals(listOf("./gradlew compileKotlin", "./gradlew test"), fakeShellRunner.executedCommands)
        }

    @Test
    @DisplayName("Should halt custom workflow when a step fails")
    fun testRunCustomWorkflowHaltOnFailure() =
        runBlocking {
            val config =
                AiKanbanConfig(
                    workflows =
                        mapOf(
                            "failing-wf" to
                                CustomWorkflowDefinition(
                                    description = "Failing flow",
                                    steps = listOf("echo step1", "echo fail-step", "echo step3"),
                                ),
                        ),
                )
            fakeShellRunner.commandResultFactory = { cmd ->
                if (cmd == "echo fail-step") {
                    CommandExecutionResult(cmd, exitCode = 1, stdout = "", stderr = "Step failed", success = false)
                } else {
                    CommandExecutionResult(cmd, exitCode = 0, stdout = "OK", stderr = "", success = true)
                }
            }
            val workflowService = createWorkflowService(config)

            val result = workflowService.runWorkflow("failing-wf")
            assertFalse(result.success)
            assertEquals(2, result.executedSteps.size) // step3 must NOT be executed
            assertEquals(listOf("echo step1", "echo fail-step"), fakeShellRunner.executedCommands)
        }

    @Test
    @DisplayName("Should throw exception when custom workflow does not exist")
    fun testRunUnknownWorkflow() =
        runBlocking {
            val config = AiKanbanConfig()
            val workflowService = createWorkflowService(config)

            assertFailsWith<IllegalArgumentException> {
                workflowService.runWorkflow("non-existent")
            }
        }

    @Test
    @DisplayName("Should atomically start existing task and move to IN_PROGRESS")
    fun testStartTask() =
        runBlocking {
            val task = service.createTask(title = "Task To Start", status = "TODO")
            val config = AiKanbanConfig()
            val workflowService = createWorkflowService(config)

            val started =
                workflowService.startTask(
                    StartTaskRequest(
                        taskId = task.id,
                        assignee = "agent-dev",
                        operator = "agent-dev",
                    ),
                )

            assertEquals("IN_PROGRESS", started.status)
            assertEquals("agent-dev", started.assignee)
            assertTrue(started.logs.any { it.toStatus == "IN_PROGRESS" && it.comment.contains("Started task") })
        }

    @Test
    @DisplayName("Should commit task changes, execute pre/post commit hooks, and log commit hash to task")
    fun testCommitTaskWithHooks() =
        runBlocking {
            val task = service.createTask(title = "Task For Commit", status = "IN_PROGRESS")
            val config =
                AiKanbanConfig(
                    hooks =
                        mapOf(
                            "pre-commit" to listOf("echo pre-hook"),
                            "post-commit" to listOf("echo post-hook"),
                        ),
                )
            val workflowService = createWorkflowService(config)

            val commitResult =
                workflowService.commitTask(
                    CommitTaskRequest(
                        taskId = task.id,
                        message = "feat(test): hook support",
                        operator = "agent-dev",
                        executeGitCommit = false,
                    ),
                )

            assertNotNull(commitResult.task)
            assertEquals(listOf("echo pre-hook", "echo post-hook"), fakeShellRunner.executedCommands)

            val updatedTask = service.getTask(task.id)
            assertTrue(updatedTask.logs.any { it.comment.contains("Committed changes: feat(test): hook support") })
        }

    @Test
    @DisplayName("Should execute pre-submit-pr hook in submitPr and abort on hook failure")
    fun testSubmitPrHooksFailure() =
        runBlocking {
            val task = service.createTask(title = "Task For PR", status = "IN_PROGRESS")
            val config =
                AiKanbanConfig(
                    hooks =
                        mapOf(
                            "pre-submit-pr" to listOf("echo pre-pr-fail"),
                        ),
                )
            fakeShellRunner.commandResultFactory = { cmd ->
                CommandExecutionResult(cmd, exitCode = 1, stdout = "", stderr = "Pre-PR checks failed", success = false)
            }
            val workflowService = createWorkflowService(config)

            assertFailsWith<IllegalStateException> {
                workflowService.submitPr(
                    SubmitPrRequest(
                        taskId = task.id,
                        headBranch = "feature/test",
                    ),
                )
            }

            // Task must NOT have transitioned to REVIEW
            val taskInDb = service.getTask(task.id)
            assertEquals("IN_PROGRESS", taskInDb.status)
        }

    @Test
    @DisplayName("Should execute pre-submit-pr and post-submit-pr hooks successfully in submitPr")
    fun testSubmitPrHooksSuccess() =
        runBlocking {
            val task = service.createTask(title = "Task For PR Success", status = "IN_PROGRESS")
            val config =
                AiKanbanConfig(
                    hooks =
                        mapOf(
                            "pre-submit-pr" to listOf("echo check-clean"),
                            "post-submit-pr" to listOf("echo notify-slack"),
                        ),
                )
            val workflowService = createWorkflowService(config)

            val result =
                workflowService.submitPr(
                    SubmitPrRequest(
                        taskId = task.id,
                        headBranch = "feature/test-success",
                    ),
                )

            assertEquals("REVIEW", result.task.status)
            assertEquals(listOf("echo check-clean", "echo notify-slack"), fakeShellRunner.executedCommands)
        }
}
