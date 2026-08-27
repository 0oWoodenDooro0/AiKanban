package aikanban.github

import aikanban.github.client.KtorGitHubClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubClientTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private val sampleIssuesJsonResponse =
        """
        [
          {
            "id": 1001,
            "number": 1,
            "title": "Setup SQLite Persistence",
            "body": "Implement repository with SQLite WAL mode",
            "state": "closed",
            "html_url": "https://github.com/0oWoodenDooro0/AiKanban/issues/1",
            "user": {
              "id": 501,
              "login": "octocat"
            },
            "assignee": {
              "id": 501,
              "login": "octocat"
            },
            "labels": [
              {
                "id": 201,
                "name": "backend",
                "color": "0075ca"
              },
              {
                "id": 202,
                "name": "priority:high",
                "color": "d93f0b"
              }
            ],
            "created_at": "2026-08-20T10:00:00Z",
            "updated_at": "2026-08-21T12:00:00Z",
            "closed_at": "2026-08-22T15:00:00Z"
          },
          {
            "id": 1002,
            "number": 2,
            "title": "Implement REST API",
            "body": "Add Ktor REST endpoints",
            "state": "open",
            "html_url": "https://github.com/0oWoodenDooro0/AiKanban/issues/2",
            "user": {
              "id": 502,
              "login": "developer"
            },
            "assignee": null,
            "labels": [
              {
                "id": 203,
                "name": "api"
              }
            ],
            "pull_request": null,
            "created_at": "2026-08-22T10:00:00Z",
            "updated_at": "2026-08-22T10:00:00Z",
            "closed_at": null
          },
          {
            "id": 1003,
            "number": 3,
            "title": "Bump dependencies PR",
            "body": "Automated dependency bump",
            "state": "open",
            "html_url": "https://github.com/0oWoodenDooro0/AiKanban/pull/3",
            "user": {
              "id": 503,
              "login": "dependabot[bot]"
            },
            "labels": [],
            "pull_request": {
              "url": "https://api.github.com/repos/0oWoodenDooro0/AiKanban/pulls/3",
              "html_url": "https://github.com/0oWoodenDooro0/AiKanban/pull/3"
            },
            "created_at": "2026-08-23T10:00:00Z"
          }
        ]
        """.trimIndent()

    private val sampleSingleIssueJsonResponse =
        """
        {
          "id": 1006,
          "number": 6,
          "title": "feat(github): implement github issue sync and link resolver",
          "body": "Implement GitHubSyncService to parse GitHub URLs and sync issues.",
          "state": "open",
          "html_url": "https://github.com/0oWoodenDooro0/AiKanban/issues/6",
          "user": {
            "id": 501,
            "login": "0oWoodenDooro0"
          },
          "assignee": null,
          "labels": [
            {
              "id": 205,
              "name": "enhancement"
            },
            {
              "id": 206,
              "name": "priority:urgent"
            }
          ]
        }
        """.trimIndent()

    @Nested
    @DisplayName("Repository Issues Fetching")
    inner class RepositoryIssuesTests {
        @Test
        @DisplayName("Should fetch repository issues and parse fields correctly")
        fun testFetchRepositoryIssuesSuccess() =
            runTest {
                val mockEngine =
                    MockEngine { request ->
                        val headers = headersOf(HttpHeaders.ContentType, "application/json")
                        if (request.url.encodedPath == "/repos/0oWoodenDooro0/AiKanban/issues") {
                            respond(sampleIssuesJsonResponse, HttpStatusCode.OK, headers)
                        } else {
                            respond("Not Found", HttpStatusCode.NotFound)
                        }
                    }

                val httpClient =
                    HttpClient(mockEngine) {
                        install(ContentNegotiation) { json(json) }
                    }

                val client = KtorGitHubClient(httpClient = httpClient)
                val issues = client.fetchRepositoryIssues("0oWoodenDooro0", "AiKanban", state = "all")

                assertEquals(3, issues.size)

                val issue1 = issues[0]
                assertEquals(1, issue1.number)
                assertEquals("Setup SQLite Persistence", issue1.title)
                assertEquals("Implement repository with SQLite WAL mode", issue1.body)
                assertEquals("closed", issue1.state)
                assertEquals("octocat", issue1.assignee?.login)
                assertEquals(2, issue1.labels.size)
                assertEquals("backend", issue1.labels[0].name)
                assertNull(issue1.pullRequest)

                val pr = issues[2]
                assertEquals(3, pr.number)
                assertNotNull(pr.pullRequest)
                assertEquals("https://github.com/0oWoodenDooro0/AiKanban/pull/3", pr.pullRequest?.htmlUrl)
            }

        @Test
        @DisplayName("Should pass token in Authorization header and custom user agent")
        fun testAuthorizationAndHeaders() =
            runTest {
                var capturedAuthHeader: String? = null
                var capturedUserAgent: String? = null

                val mockEngine =
                    MockEngine { request ->
                        capturedAuthHeader = request.headers[HttpHeaders.Authorization]
                        capturedUserAgent = request.headers[HttpHeaders.UserAgent]
                        respond(sampleIssuesJsonResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }

                val httpClient =
                    HttpClient(mockEngine) {
                        install(ContentNegotiation) { json(json) }
                    }

                val client = KtorGitHubClient(httpClient = httpClient)
                client.fetchRepositoryIssues("owner", "repo", token = "ghp_secretToken123")

                assertEquals("Bearer ghp_secretToken123", capturedAuthHeader)
                assertTrue(capturedUserAgent?.contains("AiKanban") == true)
            }
    }

    @Nested
    @DisplayName("Single Issue Fetching")
    inner class SingleIssueTests {
        @Test
        @DisplayName("Should fetch single issue by number successfully")
        fun testFetchSingleIssueSuccess() =
            runTest {
                val mockEngine =
                    MockEngine { request ->
                        if (request.url.encodedPath == "/repos/0oWoodenDooro0/AiKanban/issues/6") {
                            respond(
                                sampleSingleIssueJsonResponse,
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respond("Not Found", HttpStatusCode.NotFound)
                        }
                    }

                val httpClient =
                    HttpClient(mockEngine) {
                        install(ContentNegotiation) { json(json) }
                    }

                val client = KtorGitHubClient(httpClient = httpClient)
                val issue = client.fetchIssue("0oWoodenDooro0", "AiKanban", 6)

                assertNotNull(issue)
                assertEquals(6, issue.number)
                assertEquals("feat(github): implement github issue sync and link resolver", issue.title)
                assertEquals(2, issue.labels.size)
                assertEquals("priority:urgent", issue.labels[1].name)
            }

        @Test
        @DisplayName("Should return null when issue is not found (404)")
        fun testFetchSingleIssueNotFound() =
            runTest {
                val mockEngine =
                    MockEngine {
                        respond("Not Found", HttpStatusCode.NotFound)
                    }

                val httpClient =
                    HttpClient(mockEngine) {
                        install(ContentNegotiation) { json(json) }
                    }

                val client = KtorGitHubClient(httpClient = httpClient)
                val issue = client.fetchIssue("owner", "repo", 9999)

                assertNull(issue)
            }
    }
}
