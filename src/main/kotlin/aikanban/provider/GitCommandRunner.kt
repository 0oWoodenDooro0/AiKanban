package aikanban.provider

import java.io.File
import java.util.concurrent.TimeUnit

data class GitProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

interface GitCommandRunner {
    fun getCurrentBranch(workingDir: File? = null): String

    fun createAndCheckoutBranch(
        branchName: String,
        baseBranch: String = "main",
        workingDir: File? = null,
    ): GitProcessResult

    fun pushBranch(
        branchName: String,
        remote: String = "origin",
        setUpstream: Boolean = true,
        workingDir: File? = null,
    ): GitProcessResult

    fun isGitRepository(workingDir: File? = null): Boolean

    fun getRemoteUrl(
        remote: String = "origin",
        workingDir: File? = null,
    ): String?
}

class DefaultGitCommandRunner(
    private val defaultWorkingDir: File = File("."),
) : GitCommandRunner {
    private fun runProcess(
        args: List<String>,
        workingDir: File?,
    ): GitProcessResult {
        val dir = (workingDir ?: defaultWorkingDir).absoluteFile
        return try {
            val process =
                ProcessBuilder(args)
                    .directory(dir)
                    .redirectErrorStream(false)
                    .start()

            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val finished = process.waitFor(30, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                GitProcessResult(-1, stdout, "Process timed out after 30 seconds")
            } else {
                GitProcessResult(process.exitValue(), stdout, stderr)
            }
        } catch (e: Exception) {
            GitProcessResult(-1, "", e.message ?: "Failed to execute git process")
        }
    }

    override fun getCurrentBranch(workingDir: File?): String {
        val res = runProcess(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), workingDir)
        return if (res.exitCode == 0 && res.stdout.isNotBlank()) res.stdout else "HEAD"
    }

    override fun createAndCheckoutBranch(
        branchName: String,
        baseBranch: String,
        workingDir: File?,
    ): GitProcessResult {
        return runProcess(listOf("git", "checkout", "-b", branchName, baseBranch), workingDir)
    }

    override fun pushBranch(
        branchName: String,
        remote: String,
        setUpstream: Boolean,
        workingDir: File?,
    ): GitProcessResult {
        val args =
            if (setUpstream) {
                listOf("git", "push", "-u", remote, branchName)
            } else {
                listOf("git", "push", remote, branchName)
            }
        return runProcess(args, workingDir)
    }

    override fun isGitRepository(workingDir: File?): Boolean {
        val res = runProcess(listOf("git", "rev-parse", "--is-inside-work-tree"), workingDir)
        return res.exitCode == 0 && res.stdout == "true"
    }

    override fun getRemoteUrl(
        remote: String,
        workingDir: File?,
    ): String? {
        val res = runProcess(listOf("git", "remote", "get-url", remote), workingDir)
        return if (res.exitCode == 0 && res.stdout.isNotBlank()) res.stdout else null
    }
}
