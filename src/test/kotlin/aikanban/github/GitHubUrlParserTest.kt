package aikanban.github

import aikanban.github.model.GitHubResource
import aikanban.github.service.GitHubUrlParser
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GitHubUrlParserTest {
    @Nested
    @DisplayName("Issue URL Parsing")
    inner class IssueUrlTests {
        @Test
        @DisplayName("Should parse standard GitHub issue URL")
        fun testParseStandardIssueUrl() {
            val result = GitHubUrlParser.parse("https://github.com/0oWoodenDooro0/AiKanban/issues/6")
            assertNotNull(result)
            val issue = assertIs<GitHubResource.Issue>(result)
            assertEquals("0oWoodenDooro0", issue.owner)
            assertEquals("AiKanban", issue.repo)
            assertEquals("0oWoodenDooro0/AiKanban", issue.fullRepo)
            assertEquals(6, issue.number)
            assertEquals("https://github.com/0oWoodenDooro0/AiKanban/issues/6", issue.canonicalUrl)
        }

        @Test
        @DisplayName("Should parse issue URL with trailing slash, http scheme, or www")
        fun testParseIssueUrlVariations() {
            val res1 = GitHubUrlParser.parse("http://github.com/owner/my-repo/issues/100/")
            assertNotNull(res1)
            val issue1 = assertIs<GitHubResource.Issue>(res1)
            assertEquals("owner", issue1.owner)
            assertEquals("my-repo", issue1.repo)
            assertEquals(100, issue1.number)

            val res2 = GitHubUrlParser.parse("github.com/owner/repo/issues/42")
            assertNotNull(res2)
            val issue2 = assertIs<GitHubResource.Issue>(res2)
            assertEquals(42, issue2.number)
        }

        @Test
        @DisplayName("Should parse issue URL with query parameters and anchor fragments")
        fun testParseIssueUrlWithQueryAndFragment() {
            val url = "https://github.com/owner/repo/issues/12?notification_referrer_id=123#issuecomment-456789"
            val result = GitHubUrlParser.parse(url)
            assertNotNull(result)
            val issue = assertIs<GitHubResource.Issue>(result)
            assertEquals("owner", issue.owner)
            assertEquals("repo", issue.repo)
            assertEquals(12, issue.number)
            assertEquals("https://github.com/owner/repo/issues/12", issue.canonicalUrl)
        }
    }

    @Nested
    @DisplayName("Pull Request URL Parsing")
    inner class PullRequestUrlTests {
        @Test
        @DisplayName("Should parse standard GitHub PR URL")
        fun testParseStandardPullRequestUrl() {
            val result = GitHubUrlParser.parse("https://github.com/0oWoodenDooro0/AiKanban/pull/12")
            assertNotNull(result)
            val pr = assertIs<GitHubResource.PullRequest>(result)
            assertEquals("0oWoodenDooro0", pr.owner)
            assertEquals("AiKanban", pr.repo)
            assertEquals("0oWoodenDooro0/AiKanban", pr.fullRepo)
            assertEquals(12, pr.number)
            assertEquals("https://github.com/0oWoodenDooro0/AiKanban/pull/12", pr.canonicalUrl)
        }

        @Test
        @DisplayName("Should parse PR URL with subpath like /files or /commits")
        fun testParsePullRequestUrlWithSubpath() {
            val result = GitHubUrlParser.parse("https://github.com/owner/repo/pull/15/files#diff-12345")
            assertNotNull(result)
            val pr = assertIs<GitHubResource.PullRequest>(result)
            assertEquals("owner", pr.owner)
            assertEquals("repo", pr.repo)
            assertEquals(15, pr.number)
            assertEquals("https://github.com/owner/repo/pull/15", pr.canonicalUrl)
        }
    }

    @Nested
    @DisplayName("Repository Parsing (Full URLs & Short Form)")
    inner class RepositoryParsingTests {
        @Test
        @DisplayName("Should parse repository full URL")
        fun testParseRepositoryUrl() {
            val result = GitHubUrlParser.parse("https://github.com/0oWoodenDooro0/AiKanban")
            assertNotNull(result)
            val repo = assertIs<GitHubResource.Repository>(result)
            assertEquals("0oWoodenDooro0", repo.owner)
            assertEquals("AiKanban", repo.repo)
            assertEquals("0oWoodenDooro0/AiKanban", repo.fullRepo)
            assertEquals("https://github.com/0oWoodenDooro0/AiKanban", repo.canonicalUrl)
        }

        @Test
        @DisplayName("Should parse repository with .git suffix or trailing slash")
        fun testParseRepositoryWithGitSuffix() {
            val result = GitHubUrlParser.parse("https://github.com/owner/repo.git/")
            assertNotNull(result)
            val repo = assertIs<GitHubResource.Repository>(result)
            assertEquals("owner", repo.owner)
            assertEquals("repo", repo.repo)
        }

        @Test
        @DisplayName("Should parse short repo string 'owner/repo'")
        fun testParseShortRepoString() {
            val result = GitHubUrlParser.parse("facebook/react")
            assertNotNull(result)
            val repo = assertIs<GitHubResource.Repository>(result)
            assertEquals("facebook", repo.owner)
            assertEquals("react", repo.repo)
            assertEquals("https://github.com/facebook/react", repo.canonicalUrl)
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
        @DisplayName("Direct helper parseIssue and parseRepository methods")
        fun testDirectHelperMethods() {
            assertNotNull(GitHubUrlParser.parseIssue("https://github.com/owner/repo/issues/7"))
            assertNull(GitHubUrlParser.parseIssue("https://github.com/owner/repo/pull/7"))
            assertNull(GitHubUrlParser.parseIssue("owner/repo"))

            assertNotNull(GitHubUrlParser.parsePullRequest("https://github.com/owner/repo/pull/8"))
            assertNull(GitHubUrlParser.parsePullRequest("https://github.com/owner/repo/issues/8"))

            assertNotNull(GitHubUrlParser.parseRepository("owner/repo"))
            assertNotNull(GitHubUrlParser.parseRepository("https://github.com/owner/repo"))
            assertNull(GitHubUrlParser.parseRepository("invalid"))
        }
    }
}
