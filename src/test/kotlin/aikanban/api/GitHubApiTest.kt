package aikanban.api

import aikanban.api.dto.ErrorResponse
import aikanban.api.dto.GitHubResolveRequest
import aikanban.api.dto.GitHubResolveResponse
import aikanban.api.dto.GitHubSyncRequest
import aikanban.api.dto.GitHubSyncResponse
import aikanban.github.client.GitHubClient
import aikanban.github.model.GitHubIssueDto
import aikanban.github.model.GitHubLabelDto
import aikanban.github.service.DefaultGitHubSyncService
import aikanban.github.service.GitHubSyncService
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubApiTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var repository: SqliteTaskRepository
    private lateinit var service: KanbanService
    private lateinit var mockGitHubClient: MockGitHubClient
    private lateinit var syncService: GitHubSyncService

    private val jsonConfig =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
            encodeDefaults = true
        }

    private class MockGitHubClient : GitHubClient {
        val issues = mutableListOf<GitHubIssueDto>()

        override suspend fun fetchRepositoryIssues(
            owner: String,
            repo: String,
            state: String,
            labels: Set<String>,
            token: String?,
            page: Int,
            perPage: Int,
        ): List<GitHubIssueDto> {
            return issues
        }

        override suspend fun fetchIssue(
            owner: String,
            repo: String,
            number: Int,
            token: String?,
        ): GitHubIssueDto? {
            return issues.find { it.number == number }
        }

        override fun close() {}
    }

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("github_api_test.db").toFile()
        repository = SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}")
        service = DefaultKanbanService(repository)
        mockGitHubClient = MockGitHubClient()
        syncService = DefaultGitHubSyncService(service, mockGitHubClient)
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    private fun withGitHubApp(block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit) =
        testApplication {
            application {
                kanbanModule(service = service, json = jsonConfig, gitHubSyncService = syncService)
            }
            val client =
                createClient {
                    install(ContentNegotiation) {
                        json(jsonConfig)
                    }
                }
            block(client)
        }

    @Nested
    @DisplayName("POST /api/github/sync Endpoints")
    inner class SyncEndpointTests {
        @Test
        @DisplayName("POST /api/github/sync should sync issues from repository and return 200 OK")
        fun testSyncRepositoryEndpoint() =
            withGitHubApp { client ->
                mockGitHubClient.issues.add(
                    GitHubIssueDto(
                        id = 1,
                        number = 42,
                        title = "API Sync Task",
                        body = "Syncing from REST endpoint",
                        state = "open",
                        htmlUrl = "https://github.com/myorg/myrepo/issues/42",
                        labels = listOf(GitHubLabelDto(name = "backend")),
                    ),
                )

                val req = GitHubSyncRequest(repo = "myorg/myrepo")
                val response =
                    client.post("/api/github/sync") {
                        contentType(ContentType.Application.Json)
                        setBody(req)
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                val body: GitHubSyncResponse = response.body()
                assertEquals("myorg/myrepo", body.repo)
                assertEquals(1, body.totalFetched)
                assertEquals(1, body.createdCount)
                assertEquals(0, body.updatedCount)
                assertEquals(1, body.tasks.size)
                assertEquals("API Sync Task", body.tasks[0].title)

                // Verify saved in service
                val tasks = service.listTasks()
                assertEquals(1, tasks.size)
                assertEquals("API Sync Task", tasks[0].title)
            }

        @Test
        @DisplayName("POST /api/github/sync with URL should sync single issue")
        fun testSyncSingleUrlEndpoint() =
            withGitHubApp { client ->
                mockGitHubClient.issues.add(
                    GitHubIssueDto(
                        id = 2,
                        number = 10,
                        title = "Single URL Task",
                        state = "open",
                        htmlUrl = "https://github.com/org/repo/issues/10",
                    ),
                )

                val req = GitHubSyncRequest(url = "https://github.com/org/repo/issues/10")
                val response =
                    client.post("/api/github/sync") {
                        contentType(ContentType.Application.Json)
                        setBody(req)
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                val body: GitHubSyncResponse = response.body()
                assertEquals(1, body.createdCount)
                assertEquals("Single URL Task", body.tasks[0].title)
            }

        @Test
        @DisplayName("POST /api/github/sync should return 400 Bad Request when both repo and url are missing")
        fun testSyncValidation() =
            withGitHubApp { client ->
                val req = GitHubSyncRequest()
                val response =
                    client.post("/api/github/sync") {
                        contentType(ContentType.Application.Json)
                        setBody(req)
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status)
                val err: ErrorResponse = response.body()
                assertTrue(err.error.contains("repo", ignoreCase = true) || err.error.contains("url", ignoreCase = true))
            }
    }

    @Nested
    @DisplayName("GitHub URL Resolver Endpoints (/api/github/resolve)")
    inner class ResolveEndpointTests {
        @Test
        @DisplayName("POST /api/github/resolve should parse GitHub URL to metadata")
        fun testResolvePost() =
            withGitHubApp { client ->
                val req = GitHubResolveRequest(url = "https://github.com/0oWoodenDooro0/AiKanban/issues/6")
                val response =
                    client.post("/api/github/resolve") {
                        contentType(ContentType.Application.Json)
                        setBody(req)
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                val result: GitHubResolveResponse = response.body()
                assertEquals("0oWoodenDooro0", result.owner)
                assertEquals("AiKanban", result.repo)
                assertEquals("ISSUE", result.type)
                assertEquals(6, result.number)
                assertEquals("https://github.com/0oWoodenDooro0/AiKanban/issues/6", result.canonicalUrl)
            }

        @Test
        @DisplayName("GET /api/github/resolve should parse query param url")
        fun testResolveGet() =
            withGitHubApp { client ->
                val response = client.get("/api/github/resolve?url=https://github.com/0oWoodenDooro0/AiKanban/pull/12")
                assertEquals(HttpStatusCode.OK, response.status)
                val result: GitHubResolveResponse = response.body()
                assertEquals("0oWoodenDooro0", result.owner)
                assertEquals("AiKanban", result.repo)
                assertEquals("PULL_REQUEST", result.type)
                assertEquals(12, result.number)
            }

        @Test
        @DisplayName("GET /api/github/resolve with invalid url returns 400 Bad Request")
        fun testResolveInvalid() =
            withGitHubApp { client ->
                val response = client.get("/api/github/resolve?url=invalid-url")
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
    }
}
