package aikanban.github.client

import aikanban.github.model.GitHubIssueDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

interface GitHubClient : AutoCloseable {
    suspend fun fetchRepositoryIssues(
        owner: String,
        repo: String,
        state: String = "open",
        labels: Set<String> = emptySet(),
        token: String? = null,
        page: Int = 1,
        perPage: Int = 100,
    ): List<GitHubIssueDto>

    suspend fun fetchIssue(
        owner: String,
        repo: String,
        number: Int,
        token: String? = null,
    ): GitHubIssueDto?
}

class KtorGitHubClient(
    private val httpClient: HttpClient = createDefaultHttpClient(),
    private val apiBaseUrl: String = "https://api.github.com",
) : GitHubClient {
    companion object {
        fun createDefaultHttpClient(): HttpClient {
            return HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                            encodeDefaults = true
                        },
                    )
                }
            }
        }
    }

    private fun resolveToken(explicitToken: String?): String? {
        if (!explicitToken.isNullOrBlank()) return explicitToken
        return System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN")
    }

    override suspend fun fetchRepositoryIssues(
        owner: String,
        repo: String,
        state: String,
        labels: Set<String>,
        token: String?,
        page: Int,
        perPage: Int,
    ): List<GitHubIssueDto> {
        val authToken = resolveToken(token)
        val url = "$apiBaseUrl/repos/$owner/$repo/issues"

        val response: HttpResponse =
            httpClient.get(url) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "AiKanban")
                header("X-GitHub-Api-Version", "2022-11-28")
                if (!authToken.isNullOrBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $authToken")
                }
                parameter("state", state)
                parameter("page", page)
                parameter("per_page", perPage)
                if (labels.isNotEmpty()) {
                    parameter("labels", labels.joinToString(","))
                }
            }

        if (response.status == HttpStatusCode.NotFound) {
            return emptyList()
        }
        if (response.status != HttpStatusCode.OK) {
            val errorBody =
                try {
                    response.body<String>()
                } catch (_: Exception) {
                    ""
                }
            throw IllegalStateException("GitHub API error (${response.status.value}): $errorBody")
        }

        return response.body()
    }

    override suspend fun fetchIssue(
        owner: String,
        repo: String,
        number: Int,
        token: String?,
    ): GitHubIssueDto? {
        val authToken = resolveToken(token)
        val url = "$apiBaseUrl/repos/$owner/$repo/issues/$number"

        val response: HttpResponse =
            httpClient.get(url) {
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header(HttpHeaders.UserAgent, "AiKanban")
                header("X-GitHub-Api-Version", "2022-11-28")
                if (!authToken.isNullOrBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $authToken")
                }
            }

        if (response.status == HttpStatusCode.NotFound) {
            return null
        }
        if (response.status != HttpStatusCode.OK) {
            val errorBody =
                try {
                    response.body<String>()
                } catch (_: Exception) {
                    ""
                }
            throw IllegalStateException("GitHub API error (${response.status.value}): $errorBody")
        }

        return response.body()
    }

    override fun close() {
        httpClient.close()
    }
}
