package aikanban.api.routes

import aikanban.api.dto.CreateColumnRequest
import aikanban.api.dto.MessageResponse
import aikanban.api.dto.UpdateColumnRequest
import aikanban.model.BoardColumn
import aikanban.service.KanbanService
import aikanban.service.exception.ColumnNotFoundException
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.columnRoutes(service: KanbanService) {
    route("/api/columns") {
        get {
            val columns = service.getColumns()
            call.respond(columns)
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Column ID is required")
            val column = service.getColumn(id) ?: throw ColumnNotFoundException(id)
            call.respond(column)
        }

        post {
            val req = call.receive<CreateColumnRequest>()
            val column =
                BoardColumn(
                    id = req.id,
                    name = req.name,
                    order = req.order,
                    color = req.color,
                    isTerminal = req.isTerminal,
                )
            val created = service.createColumn(column)
            call.respond(HttpStatusCode.Created, created)
        }

        put("/{id}") {
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Column ID is required")
            val req = call.receive<UpdateColumnRequest>()
            val existing = service.getColumn(id) ?: throw ColumnNotFoundException(id)
            val updated =
                existing.copy(
                    name = req.name,
                    order = req.order,
                    color = req.color,
                    isTerminal = req.isTerminal,
                )
            val result = service.updateColumn(updated)
            call.respond(HttpStatusCode.OK, result)
        }

        delete("/{id}") {
            val id = call.parameters["id"] ?: throw IllegalArgumentException("Column ID is required")
            if (service.getColumn(id) == null) {
                throw ColumnNotFoundException(id)
            }
            val deleted = service.deleteColumn(id)
            if (deleted) {
                call.respond(HttpStatusCode.OK, MessageResponse("Column '$id' deleted successfully"))
            } else {
                throw ColumnNotFoundException(id)
            }
        }
    }
}
