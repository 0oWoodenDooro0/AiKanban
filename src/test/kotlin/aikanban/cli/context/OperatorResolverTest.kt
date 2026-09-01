package aikanban.cli.context

import aikanban.config.AiKanbanConfig
import aikanban.provider.GitCommandRunner
import aikanban.provider.GitProcessResult
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

class OperatorResolverTest {
    class FakeGitCommandRunner(
        private val userName: String? = null,
        private val throwsException: Boolean = false,
    ) : GitCommandRunner {
        override fun getCurrentBranch(workingDir: File?): String = "main"

        override fun createAndCheckoutBranch(
            branchName: String,
            baseBranch: String,
            workingDir: File?,
        ): GitProcessResult =
            GitProcessResult(
                0,
                "",
                "",
            )

        override fun pushBranch(
            branchName: String,
            remote: String,
            setUpstream: Boolean,
            workingDir: File?,
        ): GitProcessResult =
            GitProcessResult(
                0,
                "",
                "",
            )

        override fun isGitRepository(workingDir: File?): Boolean = true

        override fun getRemoteUrl(
            remote: String,
            workingDir: File?,
        ): String? = null

        override fun getUserName(workingDir: File?): String? {
            if (throwsException) throw RuntimeException("Git command failed")
            return userName
        }
    }

    @Nested
    @DisplayName("Hierarchy Priority Tests")
    inner class PriorityTests {
        @Test
        @DisplayName("1. Explicit operator takes highest priority over config, git config, and fallback")
        fun testExplicitOperatorPriority() {
            val config = AiKanbanConfig(operator = "config-user")
            val gitRunner = FakeGitCommandRunner(userName = "git-user")

            val resolved =
                OperatorResolver.resolve(
                    explicitOperator = "explicit-user",
                    config = config,
                    gitCommandRunner = gitRunner,
                    fallback = "default-fallback",
                )

            assertEquals("explicit-user", resolved)
        }

        @Test
        @DisplayName("2. Config operator takes priority over git config and fallback when explicit operator is absent")
        fun testConfigOperatorPriority() {
            val config = AiKanbanConfig(operator = "config-user")
            val gitRunner = FakeGitCommandRunner(userName = "git-user")

            val resolved =
                OperatorResolver.resolve(
                    explicitOperator = null,
                    config = config,
                    gitCommandRunner = gitRunner,
                    fallback = "default-fallback",
                )

            assertEquals("config-user", resolved)
        }

        @Test
        @DisplayName("3. Git user.name takes priority over fallback when explicit and config operator are absent")
        fun testGitConfigOperatorPriority() {
            val config = AiKanbanConfig(operator = null)
            val gitRunner = FakeGitCommandRunner(userName = "git-user")

            val resolved =
                OperatorResolver.resolve(
                    explicitOperator = null,
                    config = config,
                    gitCommandRunner = gitRunner,
                    fallback = "default-fallback",
                )

            assertEquals("git-user", resolved)
        }

        @Test
        @DisplayName("4. Fallback default 'workflow' is returned when all other sources are absent")
        fun testFallbackDefault() {
            val config = AiKanbanConfig(operator = null)
            val gitRunner = FakeGitCommandRunner(userName = null)

            val resolved =
                OperatorResolver.resolve(
                    explicitOperator = null,
                    config = config,
                    gitCommandRunner = gitRunner,
                )

            assertEquals("workflow", resolved)
        }

        @Test
        @DisplayName("5. Custom fallback is returned when specified and all sources are absent")
        fun testCustomFallback() {
            val resolved =
                OperatorResolver.resolve(
                    explicitOperator = null,
                    config = null,
                    gitCommandRunner = null,
                    fallback = "custom-fallback",
                )

            assertEquals("custom-fallback", resolved)
        }
    }

    @Nested
    @DisplayName("Edge Cases & Trimming")
    inner class EdgeCaseTests {
        @Test
        @DisplayName("Blank explicit operator falls through to config")
        fun testBlankExplicitOperatorFallsThrough() {
            val config = AiKanbanConfig(operator = "config-user")
            val resolved =
                OperatorResolver.resolve(
                    explicitOperator = "   ",
                    config = config,
                )
            assertEquals("config-user", resolved)
        }

        @Test
        @DisplayName("Blank config operator falls through to git config")
        fun testBlankConfigOperatorFallsThrough() {
            val config = AiKanbanConfig(operator = "   ")
            val gitRunner = FakeGitCommandRunner(userName = "git-user")
            val resolved =
                OperatorResolver.resolve(
                    explicitOperator = null,
                    config = config,
                    gitCommandRunner = gitRunner,
                )
            assertEquals("git-user", resolved)
        }

        @Test
        @DisplayName("Blank git user.name falls through to fallback")
        fun testBlankGitUserNameFallsThrough() {
            val gitRunner = FakeGitCommandRunner(userName = "   ")
            val resolved =
                OperatorResolver.resolve(
                    explicitOperator = null,
                    config = null,
                    gitCommandRunner = gitRunner,
                    fallback = "fallback-user",
                )
            assertEquals("fallback-user", resolved)
        }

        @Test
        @DisplayName("Git runner exception safely falls through to fallback")
        fun testGitExceptionFallsThrough() {
            val gitRunner = FakeGitCommandRunner(throwsException = true)
            val resolved =
                OperatorResolver.resolve(
                    explicitOperator = null,
                    config = null,
                    gitCommandRunner = gitRunner,
                    fallback = "fallback-user",
                )
            assertEquals("fallback-user", resolved)
        }

        @Test
        @DisplayName("Values from all sources are properly trimmed")
        fun testTrimming() {
            assertEquals(
                "trimmed-explicit",
                OperatorResolver.resolve(explicitOperator = "  trimmed-explicit  "),
            )
            assertEquals(
                "trimmed-config",
                OperatorResolver.resolve(config = AiKanbanConfig(operator = "  trimmed-config  ")),
            )
            assertEquals(
                "trimmed-git",
                OperatorResolver.resolve(gitCommandRunner = FakeGitCommandRunner(userName = "  trimmed-git  ")),
            )
        }
    }
}
