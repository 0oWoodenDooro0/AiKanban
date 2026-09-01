package aikanban.process

import aikanban.provider.DefaultGhCliRunner
import aikanban.provider.DefaultGitCommandRunner
import aikanban.workflow.DefaultShellCommandRunner
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProcessExecutorTest {
    @TempDir
    lateinit var tempDir: Path

    class FakeProcessExecutor : ProcessExecutor {
        val executedRequests = mutableListOf<ProcessRequest>()
        var responseFactory: (ProcessRequest) -> ProcessResult = {
            ProcessResult(0, "mock stdout", "")
        }

        override fun execute(request: ProcessRequest): ProcessResult {
            executedRequests.add(request)
            return responseFactory(request)
        }

        override fun executeShell(
            command: String,
            workingDir: File?,
            environment: Map<String, String>,
            timeout: Duration,
        ): ProcessResult {
            val req =
                ProcessRequest(
                    command = listOf("sh", "-c", command),
                    workingDir = workingDir,
                    environment = environment,
                    timeout = timeout,
                )
            executedRequests.add(req)
            return responseFactory(req)
        }
    }

    @Nested
    @DisplayName("ProcessResult Model Tests")
    inner class ProcessResultModelTests {
        @Test
        @DisplayName("isSuccess returns true when exitCode is 0 and false otherwise")
        fun testIsSuccess() {
            val successResult = ProcessResult(exitCode = 0, stdout = "OK", stderr = "")
            assertTrue(successResult.isSuccess)

            val failureResult = ProcessResult(exitCode = 1, stdout = "", stderr = "Error")
            assertFalse(failureResult.isSuccess)

            val timeoutResult = ProcessResult(exitCode = -1, stdout = "", stderr = "Timed out")
            assertFalse(timeoutResult.isSuccess)
        }
    }

    @Nested
    @DisplayName("DefaultProcessExecutor Execution Tests")
    inner class DefaultProcessExecutorTests {
        private val executor = DefaultProcessExecutor()

        @Test
        @DisplayName("Should execute basic command and capture stdout with exitCode 0")
        fun testExecuteBasicCommandSuccess() {
            val result =
                executor.execute(
                    command = listOf("echo", "hello world"),
                    timeout = Duration.ofSeconds(5),
                )

            assertTrue(result.isSuccess)
            assertEquals(0, result.exitCode)
            assertEquals("hello world", result.stdout)
            assertEquals("", result.stderr)
        }

        @Test
        @DisplayName("Should capture non-zero exit code and stderr from failing command")
        fun testExecuteCommandFailure() {
            val result =
                executor.executeShell(
                    command = "echo 'failing message' >&2; exit 42",
                    timeout = Duration.ofSeconds(5),
                )

            assertFalse(result.isSuccess)
            assertEquals(42, result.exitCode)
            assertEquals("failing message", result.stderr)
        }

        @Test
        @DisplayName("Should inject and propagate custom environment variables")
        fun testEnvironmentVariableInjection() {
            val result =
                executor.executeShell(
                    command = "echo \"VAL=\$CUSTOM_AIKANBAN_VAR\"",
                    environment = mapOf("CUSTOM_AIKANBAN_VAR" to "injected_secret_value"),
                    timeout = Duration.ofSeconds(5),
                )

            assertTrue(result.isSuccess)
            assertEquals("VAL=injected_secret_value", result.stdout)
        }

        @Test
        @DisplayName("Should execute process in specified working directory")
        fun testWorkingDirectoryConfiguration() {
            val targetSubdir = tempDir.resolve("subdir").toFile()
            targetSubdir.mkdirs()
            val markerFile = File(targetSubdir, "marker.txt")
            markerFile.writeText("sample content")

            val result =
                executor.executeShell(
                    command = "cat marker.txt",
                    workingDir = targetSubdir,
                    timeout = Duration.ofSeconds(5),
                )

            assertTrue(result.isSuccess)
            assertEquals("sample content", result.stdout)
        }

        @Test
        @DisplayName("Should forcibly terminate process on timeout and report failure")
        fun testProcessTimeoutTermination() {
            val startTime = System.currentTimeMillis()
            val result =
                executor.executeShell(
                    command = "sleep 5",
                    timeout = Duration.ofMillis(200),
                )
            val elapsed = System.currentTimeMillis() - startTime

            assertFalse(result.isSuccess)
            assertEquals(-1, result.exitCode)
            assertTrue(result.stderr.contains("timed out", ignoreCase = true))
            assertTrue(elapsed < 4000, "Process should be destroyed quickly on timeout, elapsed: ${elapsed}ms")
        }

        @Test
        @DisplayName("Should catch startup exceptions for non-existent commands gracefully")
        fun testNonExistentCommandReturnsErrorResult() {
            val result =
                executor.execute(
                    command = listOf("non_existent_binary_for_test_12345"),
                    timeout = Duration.ofSeconds(2),
                )

            assertFalse(result.isSuccess)
            assertEquals(-1, result.exitCode)
            assertTrue(result.stderr.isNotBlank())
        }

        @Test
        @DisplayName("Should construct correct shell command on Windows and Unix platforms")
        fun testCrossPlatformShellCommandWrapping() {
            val unixExecutor = DefaultProcessExecutor(isWindows = false)
            val unixResult =
                unixExecutor.executeShell(
                    command = "echo unix_shell_test",
                    timeout = Duration.ofSeconds(5),
                )
            assertTrue(unixResult.isSuccess)
            assertEquals("unix_shell_test", unixResult.stdout)

            // Test command structure for Windows executor
            var capturedCmd: List<String>? = null
            val customWinExecutor =
                object : DefaultProcessExecutor(isWindows = true) {
                    override fun execute(request: ProcessRequest): ProcessResult {
                        capturedCmd = request.command
                        return ProcessResult(0, "win ok", "")
                    }
                }
            customWinExecutor.executeShell("echo win_test")
            assertNotNull(capturedCmd)
            assertEquals(listOf("cmd.exe", "/c", "echo win_test"), capturedCmd)
        }
    }

    @Nested
    @DisplayName("Adapter Seam Delegation Tests")
    inner class AdapterDelegationTests {
        private val fakeExecutor = FakeProcessExecutor()

        @Test
        @DisplayName("DefaultGitCommandRunner delegates process execution to ProcessExecutor")
        fun testGitCommandRunnerDelegation() {
            fakeExecutor.responseFactory = { req ->
                if (req.command.contains("rev-parse") && req.command.contains("HEAD")) {
                    ProcessResult(0, "main\n", "")
                } else {
                    ProcessResult(0, "ok", "")
                }
            }

            val gitRunner =
                DefaultGitCommandRunner(
                    processExecutor = fakeExecutor,
                    defaultWorkingDir = tempDir.toFile(),
                )

            val branch = gitRunner.getCurrentBranch()
            assertEquals("main", branch)

            val lastReq = fakeExecutor.executedRequests.lastOrNull()
            assertNotNull(lastReq)
            assertEquals(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), lastReq.command)
            assertEquals(tempDir.toFile().absoluteFile, lastReq.workingDir)
        }

        @Test
        @DisplayName("DefaultGhCliRunner delegates and injects GITHUB_TOKEN into ProcessExecutor")
        fun testGhCliRunnerDelegationAndTokenInjection() {
            fakeExecutor.responseFactory = {
                ProcessResult(0, "https://github.com/owner/repo/pull/1", "")
            }

            val ghRunner = DefaultGhCliRunner(processExecutor = fakeExecutor)
            val result =
                ghRunner.runGh(
                    args = listOf("pr", "view", "1"),
                    workingDir = tempDir.toFile(),
                    token = "ghp_secret_token_123",
                )

            assertEquals(0, result.exitCode)
            assertEquals("https://github.com/owner/repo/pull/1", result.stdout)

            val lastReq = fakeExecutor.executedRequests.lastOrNull()
            assertNotNull(lastReq)
            assertEquals(listOf("gh", "pr", "view", "1"), lastReq.command)
            assertEquals(mapOf("GITHUB_TOKEN" to "ghp_secret_token_123"), lastReq.environment)
            assertEquals(tempDir.toFile().absoluteFile, lastReq.workingDir)
        }

        @Test
        @DisplayName("DefaultShellCommandRunner delegates execute to ProcessExecutor.executeShell")
        fun testShellCommandRunnerDelegation() {
            fakeExecutor.responseFactory = {
                ProcessResult(0, "build success", "")
            }

            val shellRunner = DefaultShellCommandRunner(processExecutor = fakeExecutor)
            val result =
                shellRunner.execute(
                    command = "./gradlew build",
                    workingDir = tempDir.toFile(),
                )

            assertTrue(result.success)
            assertEquals(0, result.exitCode)
            assertEquals("build success", result.stdout)
            assertEquals("./gradlew build", result.command)

            val lastReq = fakeExecutor.executedRequests.lastOrNull()
            assertNotNull(lastReq)
            assertTrue(lastReq.command.contains("./gradlew build"))
        }
    }
}
