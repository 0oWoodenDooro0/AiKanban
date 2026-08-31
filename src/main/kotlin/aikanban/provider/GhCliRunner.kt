package aikanban.provider

import java.io.File
import java.util.concurrent.TimeUnit

interface GhCliRunner {
    fun runGh(
        args: List<String>,
        workingDir: File = File("."),
        token: String? = null,
    ): GitProcessResult
}

class DefaultGhCliRunner : GhCliRunner {
    override fun runGh(
        args: List<String>,
        workingDir: File,
        token: String?,
    ): GitProcessResult {
        return try {
            val process =
                ProcessBuilder(listOf("gh") + args)
                    .directory(workingDir.absoluteFile)
                    .redirectErrorStream(false)
                    .apply {
                        if (!token.isNullOrBlank()) {
                            environment()["GITHUB_TOKEN"] = token
                        }
                    }
                    .start()

            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val finished = process.waitFor(30, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                GitProcessResult(-1, stdout, "gh command timed out after 30 seconds")
            } else {
                GitProcessResult(process.exitValue(), stdout, stderr)
            }
        } catch (e: Exception) {
            GitProcessResult(-1, "", e.message ?: "Failed to execute gh CLI")
        }
    }
}
