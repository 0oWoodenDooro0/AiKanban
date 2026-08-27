package aikanban.api.routes

import aikanban.service.KanbanService
import aikanban.service.event.KanbanEvent
import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.json.Json

fun Route.eventRoutes(
    service: KanbanService,
    json: Json,
) {
    sse("/api/events") {
        send(ServerSentEvent(comments = "connected"))
        service.events.collect { event ->
            val eventType = event::class.simpleName ?: "KanbanEvent"
            val eventJson = json.encodeToString(KanbanEvent.serializer(), event)
            send(ServerSentEvent(data = eventJson, event = eventType))
        }
    }
}
