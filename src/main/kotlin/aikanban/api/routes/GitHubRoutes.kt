package aikanban.api.routes

import aikanban.api.dto.GitHubResolveRequest
import aikanban.api.dto.GitHubResolveResponse
import aikanban.api.dto.GitHubSyncRequest
import aikanban.api.dto.GitHubSyncResponse
import aikanban.github.model.GitHubResource
import aikanban.github.service.DefaultGitHubSyncService
import aikanban.github.service.GitHubSyncService
import aikanban.github.service.GitHubUrlParser
import aikanban.service.KanbanService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.gitHubRoutes(
    service: KanbanService,
    gitHubSyncService: GitHubSyncService? = null,
) {
    val syncService = gitHubSyncService ?: DefaultGitHubSyncService(service)

    route("/api/github") {
        post("/sync") {
            val req = call.receive<GitHubSyncRequest>()
            val repoParam = req.repo?.trim()
            val urlParam = req.url?.trim()

            if (repoParam.isNullOrBlank() && urlParam.isNullOrBlank()) {
                throw IllegalArgumentException("Either 'repo' or 'url' must be specified in the sync request.")
            }

            val syncResult =
                if (!urlParam.isNullOrBlank()) {
                    syncService.syncUrl(
                        url = urlParam,
                        targetStatus = req.targetStatus,
                        token = req.token,
                        operator = req.operator,
                        dryRun = req.dryRun,
                    )
                } else {
                    syncService.syncRepository(
                        repo = repoParam!!,
                        state = req.state,
                        labels = req.tags,
                        includePullRequests = req.includePrs,
                        targetStatus = req.targetStatus,
                        token = req.token,
                        operator = req.operator,
                        dryRun = req.dryRun,
                    )
                }

            val response =
                GitHubSyncResponse(
                    repo = syncResult.repo,
                    totalFetched = syncResult.totalFetched,
                    createdCount = syncResult.createdCount,
                    updatedCount = syncResult.updatedCount,
                    skippedCount = syncResult.skippedCount,
                    tasks = syncResult.tasks,
                    errors = syncResult.errors,
                )
            call.respond(HttpStatusCode.OK, response)
        }

        post("/resolve") {
            val req = call.receive<GitHubResolveRequest>()
            val resource =
                GitHubUrlParser.parse(req.url)
                    ?: throw IllegalArgumentException("Invalid GitHub URL: ${req.url}")

            val number =
                when (resource) {
                    is GitHubResource.Issue -> resource.number
                    is GitHubResource.PullRequest -> resource.number
                    is GitHubResource.Repository -> null
                }

            val response =
                GitHubResolveResponse(
                    owner = resource.owner,
                    repo = resource.repo,
                    type = resource.type.name,
                    number = number,
                    canonicalUrl = resource.canonicalUrl,
                )
            call.respond(HttpStatusCode.OK, response)
        }

        get("/resolve") {
            val urlParam = call.request.queryParameters["url"]
            if (urlParam.isNullOrBlank()) {
                throw IllegalArgumentException("Query parameter 'url' is required.")
            }

            val resource =
                GitHubUrlParser.parse(urlParam)
                    ?: throw IllegalArgumentException("Invalid GitHub URL: $urlParam")

            val number =
                when (resource) {
                    is GitHubResource.Issue -> resource.number
                    is GitHubResource.PullRequest -> resource.number
                    is GitHubResource.Repository -> null
                }

            val response =
                GitHubResolveResponse(
                    owner = resource.owner,
                    repo = resource.repo,
                    type = resource.type.name,
                    number = number,
                    canonicalUrl = resource.canonicalUrl,
                )
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
