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

    fun createBranchOnly(
        branchName: String,
        baseBranch: String = "main",
        workingDir: File? = null,
    ): GitProcessResult = GitProcessResult(0, "", "")

    fun pushBranch(
        branchName: String,
        remote: String = "origin",
        setUpstream: Boolean = true,
        workingDir: File? = null,
    ): GitProcessResult

    fun checkoutBranch(
        branchName: String,
        createIfMissing: Boolean = false,
        baseBranch: String? = null,
        workingDir: File? = null,
    ): GitProcessResult = GitProcessResult(0, "", "")

    fun deleteBranch(
        branchName: String,
        force: Boolean = false,
        remote: Boolean = false,
        remoteName: String = "origin",
        workingDir: File? = null,
    ): GitProcessResult = GitProcessResult(0, "", "")

    fun mergeBranch(
        branchName: String,
        squash: Boolean = false,
        workingDir: File? = null,
    ): GitProcessResult = GitProcessResult(0, "", "")

    fun pull(
        remote: String = "origin",
        branch: String? = null,
        workingDir: File? = null,
    ): GitProcessResult = GitProcessResult(0, "", "")

    fun isGitRepository(workingDir: File? = null): Boolean

    fun getRemoteUrl(
        remote: String = "origin",
        workingDir: File? = null,
    ): String?

    fun addFiles(
        files: List<String> = emptyList(),
        workingDir: File? = null,
    ): GitProcessResult = GitProcessResult(0, "", "")

    fun commit(
        message: String,
        workingDir: File? = null,
    ): GitProcessResult = GitProcessResult(0, "", "")

    fun getHeadCommitHash(workingDir: File? = null): String? = null

    fun getUserName(workingDir: File? = null): String? = null
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

    override fun createBranchOnly(
        branchName: String,
        baseBranch: String,
        workingDir: File?,
    ): GitProcessResult {
        return runProcess(listOf("git", "branch", branchName, baseBranch), workingDir)
    }

    override fun checkoutBranch(
        branchName: String,
        createIfMissing: Boolean,
        baseBranch: String?,
        workingDir: File?,
    ): GitProcessResult {
        val args =
            if (createIfMissing) {
                if (baseBranch != null) {
                    listOf("git", "checkout", "-B", branchName, baseBranch)
                } else {
                    listOf("git", "checkout", "-B", branchName)
                }
            } else {
                listOf("git", "checkout", branchName)
            }
        return runProcess(args, workingDir)
    }

    override fun deleteBranch(
        branchName: String,
        force: Boolean,
        remote: Boolean,
        remoteName: String,
        workingDir: File?,
    ): GitProcessResult {
        return if (remote) {
            runProcess(listOf("git", "push", remoteName, "--delete", branchName), workingDir)
        } else {
            val flag = if (force) "-D" else "-d"
            runProcess(listOf("git", "branch", flag, branchName), workingDir)
        }
    }

    override fun mergeBranch(
        branchName: String,
        squash: Boolean,
        workingDir: File?,
    ): GitProcessResult {
        val args =
            if (squash) {
                listOf("git", "merge", "--squash", branchName)
            } else {
                listOf("git", "merge", branchName)
            }
        return runProcess(args, workingDir)
    }

    override fun pull(
        remote: String,
        branch: String?,
        workingDir: File?,
    ): GitProcessResult {
        val args = if (branch != null) listOf("git", "pull", remote, branch) else listOf("git", "pull", remote)
        return runProcess(args, workingDir)
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

    override fun addFiles(
        files: List<String>,
        workingDir: File?,
    ): GitProcessResult {
        val args = if (files.isEmpty()) listOf("git", "add", ".") else listOf("git", "add") + files
        return runProcess(args, workingDir)
    }

    override fun commit(
        message: String,
        workingDir: File?,
    ): GitProcessResult {
        return runProcess(listOf("git", "commit", "-m", message), workingDir)
    }

    override fun getHeadCommitHash(workingDir: File?): String? {
        val res = runProcess(listOf("git", "rev-parse", "HEAD"), workingDir)
        return if (res.exitCode == 0 && res.stdout.isNotBlank()) res.stdout else null
    }

    override fun getUserName(workingDir: File?): String? {
        val res = runProcess(listOf("git", "config", "user.name"), workingDir)
        return if (res.exitCode == 0 && res.stdout.isNotBlank()) res.stdout else null
    }
}
