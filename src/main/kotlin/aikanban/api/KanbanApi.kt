package aikanban.api

import aikanban.api.dto.ErrorResponse
import aikanban.api.routes.columnRoutes
import aikanban.api.routes.eventRoutes
import aikanban.api.routes.gitHubRoutes
import aikanban.api.routes.taskRoutes
import aikanban.config.AiKanbanConfig
import aikanban.github.service.GitHubSyncService
import aikanban.provider.ProviderFactory
import aikanban.service.KanbanService
import aikanban.service.exception.ColumnNotFoundException
import aikanban.service.exception.ColumnValidationException
import aikanban.service.exception.TaskAlreadyClaimedException
import aikanban.service.exception.TaskNotFoundException
import aikanban.service.exception.TaskValidationException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json

val DefaultApiJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = false
    }

fun Application.kanbanModule(
    service: KanbanService,
    json: Json = DefaultApiJson,
    providerFactory: ProviderFactory = ProviderFactory(service),
    config: AiKanbanConfig = AiKanbanConfig(),
    gitHubSyncService: GitHubSyncService? = null,
) {
    install(ContentNegotiation) {
        json(json)
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.Accept)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
    }

    install(SSE)

    install(StatusPages) {
        exception<TaskNotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(cause.message ?: "Task not found", HttpStatusCode.NotFound.value),
            )
        }
        exception<ColumnNotFoundException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(cause.message ?: "Column not found", HttpStatusCode.NotFound.value),
            )
        }
        exception<TaskValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Invalid task data", HttpStatusCode.BadRequest.value),
            )
        }
        exception<ColumnValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Invalid column data", HttpStatusCode.BadRequest.value),
            )
        }
        exception<TaskAlreadyClaimedException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponse(cause.message ?: "Task already claimed", HttpStatusCode.Conflict.value),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(cause.message ?: "Bad request", HttpStatusCode.BadRequest.value),
            )
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(cause.message ?: "Internal server error", HttpStatusCode.InternalServerError.value),
            )
        }
    }

    routing {
        columnRoutes(service)
        taskRoutes(service)
        gitHubRoutes(service, providerFactory, config)
        eventRoutes(service, json)
        staticResources("/", "web", index = "index.html")
    }
}

fun createKanbanServer(
    port: Int = 8080,
    host: String = "0.0.0.0",
    service: KanbanService,
    json: Json = DefaultApiJson,
    providerFactory: ProviderFactory = ProviderFactory(service),
    config: AiKanbanConfig = AiKanbanConfig(),
    gitHubSyncService: GitHubSyncService? = null,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    return embeddedServer(Netty, port = port, host = host) {
        kanbanModule(service, json, providerFactory, config, gitHubSyncService)
    }
}

fun startKanbanServer(
    port: Int = 8080,
    host: String = "0.0.0.0",
    service: KanbanService,
    wait: Boolean = true,
    providerFactory: ProviderFactory = ProviderFactory(service),
    config: AiKanbanConfig = AiKanbanConfig(),
    gitHubSyncService: GitHubSyncService? = null,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val server =
        createKanbanServer(
            port = port,
            host = host,
            service = service,
            providerFactory = providerFactory,
            config = config,
            gitHubSyncService = gitHubSyncService,
        )
    server.start(wait = wait)
    return server
}
