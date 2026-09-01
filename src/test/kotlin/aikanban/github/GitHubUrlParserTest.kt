package aikanban.github

import aikanban.github.service.GitHubUrlParser
import aikanban.provider.ResourceType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GitHubUrlParserTest {
    @Nested
    @DisplayName("Issue URL Parsing")
    inner class IssueUrlTests {
        @Test
        @DisplayName("Should parse standard GitHub issue URL to ResolvedResource")
        fun testParseStandardIssueUrl() {
            val result = GitHubUrlParser.parse("https://github.com/0oWoodenDooro0/AiKanban/issues/6")
            assertNotNull(result)
            assertEquals("github", result.provider)
            assertEquals("0oWoodenDooro0", result.owner)
            assertEquals("AiKanban", result.repo)
            assertEquals(ResourceType.ISSUE, result.type)
            assertEquals(6, result.number)
            assertEquals("https://github.com/0oWoodenDooro0/AiKanban/issues/6", result.canonicalUrl)
        }

        @Test
        @DisplayName("Should parse issue URL with trailing slash, http scheme, or www")
        fun testParseIssueUrlVariations() {
            val res1 = GitHubUrlParser.parse("http://github.com/owner/my-repo/issues/100/")
            assertNotNull(res1)
            assertEquals("github", res1.provider)
            assertEquals("owner", res1.owner)
            assertEquals("my-repo", res1.repo)
            assertEquals(ResourceType.ISSUE, res1.type)
            assertEquals(100, res1.number)

            val res2 = GitHubUrlParser.parse("github.com/owner/repo/issues/42")
            assertNotNull(res2)
            assertEquals("github", res2.provider)
            assertEquals("owner", res2.owner)
            assertEquals("repo", res2.repo)
            assertEquals(ResourceType.ISSUE, res2.type)
            assertEquals(42, res2.number)
        }

        @Test
        @DisplayName("Should parse issue URL with query parameters and anchor fragments")
        fun testParseIssueUrlWithQueryAndFragment() {
            val url = "https://github.com/owner/repo/issues/12?notification_referrer_id=123#issuecomment-456789"
            val result = GitHubUrlParser.parse(url)
            assertNotNull(result)
            assertEquals("github", result.provider)
            assertEquals("owner", result.owner)
            assertEquals("repo", result.repo)
            assertEquals(ResourceType.ISSUE, result.type)
            assertEquals(12, result.number)
            assertEquals("https://github.com/owner/repo/issues/12", result.canonicalUrl)
        }
    }

    @Nested
    @DisplayName("Pull Request URL Parsing")
    inner class PullRequestUrlTests {
        @Test
        @DisplayName("Should parse standard GitHub PR URL to ResolvedResource")
        fun testParseStandardPullRequestUrl() {
            val result = GitHubUrlParser.parse("https://github.com/0oWoodenDooro0/AiKanban/pull/12")
            assertNotNull(result)
            assertEquals("github", result.provider)
            assertEquals("0oWoodenDooro0", result.owner)
            assertEquals("AiKanban", result.repo)
            assertEquals(ResourceType.PULL_REQUEST, result.type)
            assertEquals(12, result.number)
            assertEquals("https://github.com/0oWoodenDooro0/AiKanban/pull/12", result.canonicalUrl)
        }

        @Test
        @DisplayName("Should parse PR URL with subpath like /files or /commits")
        fun testParsePullRequestUrlWithSubpath() {
            val result = GitHubUrlParser.parse("https://github.com/owner/repo/pull/15/files#diff-12345")
            assertNotNull(result)
            assertEquals("github", result.provider)
            assertEquals("owner", result.owner)
            assertEquals("repo", result.repo)
            assertEquals(ResourceType.PULL_REQUEST, result.type)
            assertEquals(15, result.number)
            assertEquals("https://github.com/owner/repo/pull/15", result.canonicalUrl)
        }
    }

    @Nested
    @DisplayName("Repository Parsing (Full URLs & Short Form)")
    inner class RepositoryParsingTests {
        @Test
        @DisplayName("Should parse repository full URL to ResolvedResource")
        fun testParseRepositoryUrl() {
            val result = GitHubUrlParser.parse("https://github.com/0oWoodenDooro0/AiKanban")
            assertNotNull(result)
            assertEquals("github", result.provider)
            assertEquals("0oWoodenDooro0", result.owner)
            assertEquals("AiKanban", result.repo)
            assertEquals(ResourceType.REPOSITORY, result.type)
            assertNull(result.number)
            assertEquals("https://github.com/0oWoodenDooro0/AiKanban", result.canonicalUrl)
        }

        @Test
        @DisplayName("Should parse repository with .git suffix or trailing slash")
        fun testParseRepositoryWithGitSuffix() {
            val result = GitHubUrlParser.parse("https://github.com/owner/repo.git/")
            assertNotNull(result)
            assertEquals("github", result.provider)
            assertEquals("owner", result.owner)
            assertEquals("repo", result.repo)
            assertEquals(ResourceType.REPOSITORY, result.type)
            assertNull(result.number)
        }

        @Test
        @DisplayName("Should parse short repo string 'owner/repo'")
        fun testParseShortRepoString() {
            val result = GitHubUrlParser.parse("facebook/react")
            assertNotNull(result)
            assertEquals("github", result.provider)
            assertEquals("facebook", result.owner)
            assertEquals("react", result.repo)
            assertEquals(ResourceType.REPOSITORY, result.type)
            assertNull(result.number)
            assertEquals("https://github.com/facebook/react", result.canonicalUrl)
        }
    }

    @Nested
    @DisplayName("Invalid & Edge Case Inputs")
    inner class InvalidUrlTests {
        @Test
        @DisplayName("Should return null for blank, invalid, or non-GitHub URLs")
        fun testParseInvalidInputs() {
            assertNull(GitHubUrlParser.parse(""))
            assertNull(GitHubUrlParser.parse("   "))
            assertNull(GitHubUrlParser.parse("invalid-url-string"))
            assertNull(GitHubUrlParser.parse("https://gitlab.com/owner/repo"))
            assertNull(GitHubUrlParser.parse("https://github.com/"))
            assertNull(GitHubUrlParser.parse("https://github.com/onlyowner"))
        }

        @Test
        @DisplayName("Direct helper parseIssue, parsePullRequest, and parseRepository methods")
        fun testDirectHelperMethods() {
            val issueRes = GitHubUrlParser.parseIssue("https://github.com/owner/repo/issues/7")
            assertNotNull(issueRes)
            assertEquals(ResourceType.ISSUE, issueRes.type)
            assertEquals(7, issueRes.number)

            assertNull(GitHubUrlParser.parseIssue("https://github.com/owner/repo/pull/7"))
            assertNull(GitHubUrlParser.parseIssue("owner/repo"))

            val prRes = GitHubUrlParser.parsePullRequest("https://github.com/owner/repo/pull/8")
            assertNotNull(prRes)
            assertEquals(ResourceType.PULL_REQUEST, prRes.type)
            assertEquals(8, prRes.number)

            assertNull(GitHubUrlParser.parsePullRequest("https://github.com/owner/repo/issues/8"))

            val repoRes1 = GitHubUrlParser.parseRepository("owner/repo")
            assertNotNull(repoRes1)
            assertEquals(ResourceType.REPOSITORY, repoRes1.type)
            assertEquals("owner", repoRes1.owner)
            assertEquals("repo", repoRes1.repo)

            val repoRes2 = GitHubUrlParser.parseRepository("https://github.com/owner/repo")
            assertNotNull(repoRes2)
            assertEquals(ResourceType.REPOSITORY, repoRes2.type)

            val repoFromIssue = GitHubUrlParser.parseRepository("https://github.com/owner/repo/issues/10")
            assertNotNull(repoFromIssue)
            assertEquals(ResourceType.REPOSITORY, repoFromIssue.type)
            assertEquals("owner", repoFromIssue.owner)
            assertEquals("repo", repoFromIssue.repo)

            assertNull(GitHubUrlParser.parseRepository("invalid"))
        }
    }
}
