package aikanban.provider

import aikanban.process.DefaultProcessExecutor
import aikanban.process.ProcessExecutor
import java.io.File
import java.time.Duration

interface GhCliRunner {
    fun runGh(
        args: List<String>,
        workingDir: File = File("."),
        token: String? = null,
    ): GitProcessResult
}

class DefaultGhCliRunner(
    private val processExecutor: ProcessExecutor = DefaultProcessExecutor(),
) : GhCliRunner {
    override fun runGh(
        args: List<String>,
        workingDir: File,
        token: String?,
    ): GitProcessResult {
        val env = if (!token.isNullOrBlank()) mapOf("GITHUB_TOKEN" to token) else emptyMap()
        val result =
            processExecutor.execute(
                command = listOf("gh") + args,
                workingDir = workingDir.absoluteFile,
                environment = env,
                timeout = Duration.ofSeconds(30),
            )
        if (result.exitCode == -1 && result.stderr.startsWith("Process timed out")) {
            return result.copy(stderr = "gh command timed out after 30 seconds")
        }
        return result
    }
}
