package aikanban.api.routes

import aikanban.api.dto.GitHubResolveRequest
import aikanban.api.dto.GitHubResolveResponse
import aikanban.api.dto.GitHubSyncRequest
import aikanban.api.dto.GitHubSyncResponse
import aikanban.config.AiKanbanConfig
import aikanban.provider.ProviderFactory
import aikanban.provider.ProviderSyncRequest
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
    providerFactory: ProviderFactory = ProviderFactory(service),
    config: AiKanbanConfig = AiKanbanConfig(),
) {
    route("/api/github") {
        post("/sync") {
            val req = call.receive<GitHubSyncRequest>()
            val repoParam = req.repo?.trim()
            val urlParam = req.url?.trim()

            if (repoParam.isNullOrBlank() && urlParam.isNullOrBlank()) {
                throw IllegalArgumentException("Either 'repo' or 'url' must be specified in the sync request.")
            }

            val provider = providerFactory.resolve("github", config)
            val syncResult =
                provider.sync(
                    ProviderSyncRequest(
                        repoOrUrl = urlParam ?: repoParam,
                        state = req.state,
                        labels = req.tags,
                        includePullRequests = req.includePrs,
                        targetStatus = req.targetStatus,
                        token = req.token,
                        operator = req.operator,
                        dryRun = req.dryRun,
                    ),
                )

            val response =
                GitHubSyncResponse(
                    repo = syncResult.repo ?: "",
                    totalFetched = syncResult.totalFetched,
                    createdCount = syncResult.createdCount,
                    updatedCount = syncResult.updatedCount,
                    skippedCount = syncResult.skippedCount,
                    tasks = syncResult.tasks,
                    errors = emptyList(),
                )
            call.respond(HttpStatusCode.OK, response)
        }

        post("/resolve") {
            val req = call.receive<GitHubResolveRequest>()
            val provider = providerFactory.resolve("github", config)
            val resource =
                provider.resolveResource(req.url)
                    ?: throw IllegalArgumentException("Invalid GitHub URL: ${req.url}")

            val response =
                GitHubResolveResponse(
                    owner = resource.owner ?: "",
                    repo = resource.repo ?: "",
                    type = resource.type.name,
                    number = resource.number,
                    canonicalUrl = resource.canonicalUrl,
                )
            call.respond(HttpStatusCode.OK, response)
        }

        get("/resolve") {
            val urlParam = call.request.queryParameters["url"]
            if (urlParam.isNullOrBlank()) {
                throw IllegalArgumentException("Query parameter 'url' is required.")
            }

            val provider = providerFactory.resolve("github", config)
            val resource =
                provider.resolveResource(urlParam)
                    ?: throw IllegalArgumentException("Invalid GitHub URL: $urlParam")

            val response =
                GitHubResolveResponse(
                    owner = resource.owner ?: "",
                    repo = resource.repo ?: "",
                    type = resource.type.name,
                    number = resource.number,
                    canonicalUrl = resource.canonicalUrl,
                )
            call.respond(HttpStatusCode.OK, response)
        }
    }
}
