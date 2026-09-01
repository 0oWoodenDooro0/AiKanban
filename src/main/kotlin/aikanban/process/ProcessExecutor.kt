package aikanban.process

import java.io.File
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ProcessRequest(
    val command: List<String>,
    val workingDir: File? = null,
    val environment: Map<String, String> = emptyMap(),
    val timeout: Duration = Duration.ofSeconds(30),
)

data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
}

interface ProcessExecutor {
    fun execute(request: ProcessRequest): ProcessResult

    fun execute(
        command: List<String>,
        workingDir: File? = null,
        environment: Map<String, String> = emptyMap(),
        timeout: Duration = Duration.ofSeconds(30),
    ): ProcessResult = execute(ProcessRequest(command, workingDir, environment, timeout))

    fun executeShell(
        command: String,
        workingDir: File? = null,
        environment: Map<String, String> = emptyMap(),
        timeout: Duration = Duration.ofSeconds(60),
    ): ProcessResult
}

open class DefaultProcessExecutor(
    private val isWindows: Boolean = System.getProperty("os.name")?.contains("win", ignoreCase = true) == true,
) : ProcessExecutor {
    override fun execute(request: ProcessRequest): ProcessResult {
        return try {
            val processBuilder =
                ProcessBuilder(request.command)
                    .redirectErrorStream(false)
                    .apply {
                        if (request.workingDir != null) {
                            directory(request.workingDir.absoluteFile)
                        }
                        if (request.environment.isNotEmpty()) {
                            environment().putAll(request.environment)
                        }
                    }

            val process = processBuilder.start()

            val readerPool =
                Executors.newFixedThreadPool(2) { r ->
                    Thread(r).apply { isDaemon = true }
                }

            try {
                val stdoutFuture =
                    readerPool.submit(
                        Callable {
                            process.inputStream.bufferedReader().readText().trim()
                        },
                    )
                val stderrFuture =
                    readerPool.submit(
                        Callable {
                            process.errorStream.bufferedReader().readText().trim()
                        },
                    )

                val finished = process.waitFor(request.timeout.toMillis(), TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    readerPool.shutdownNow()
                    val partialStdout =
                        try {
                            stdoutFuture.get(50, TimeUnit.MILLISECONDS)
                        } catch (e: Exception) {
                            ""
                        }
                    val timeoutStr =
                        if (request.timeout.toMillis() % 1000L == 0L && request.timeout.toSeconds() > 0) {
                            "${request.timeout.toSeconds()} seconds"
                        } else {
                            "${request.timeout.toMillis()} ms"
                        }
                    ProcessResult(
                        exitCode = -1,
                        stdout = partialStdout,
                        stderr = "Process timed out after $timeoutStr",
                    )
                } else {
                    val stdout =
                        try {
                            stdoutFuture.get(1000, TimeUnit.MILLISECONDS)
                        } catch (e: Exception) {
                            ""
                        }
                    val stderr =
                        try {
                            stderrFuture.get(1000, TimeUnit.MILLISECONDS)
                        } catch (e: Exception) {
                            ""
                        }
                    ProcessResult(
                        exitCode = process.exitValue(),
                        stdout = stdout,
                        stderr = stderr,
                    )
                }
            } finally {
                readerPool.shutdown()
            }
        } catch (e: Exception) {
            ProcessResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Failed to execute process",
            )
        }
    }

    override fun executeShell(
        command: String,
        workingDir: File?,
        environment: Map<String, String>,
        timeout: Duration,
    ): ProcessResult {
        val shellCommand =
            if (isWindows) {
                listOf("cmd.exe", "/c", command)
            } else {
                listOf("sh", "-c", command)
            }
        return execute(
            ProcessRequest(
                command = shellCommand,
                workingDir = workingDir,
                environment = environment,
                timeout = timeout,
            ),
        )
    }
}
