package aikanban.api.routes

import aikanban.api.dto.AddCommentRequest
import aikanban.api.dto.ClaimTaskRequest
import aikanban.api.dto.CreateTaskRequest
import aikanban.api.dto.MessageResponse
import aikanban.api.dto.MoveTaskRequest
import aikanban.api.dto.ReleaseTaskRequest
import aikanban.api.dto.UpdateTaskRequest
import aikanban.model.TaskPriority
import aikanban.service.KanbanService
import aikanban.service.exception.TaskNotFoundException
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.taskRoutes(service: KanbanService) {
    route("/api/tasks") {
        get {
            val status = call.request.queryParameters["status"]
            val assignee = call.request.queryParameters["assignee"]
            val tag = call.request.queryParameters["tag"]
            val priorityParam = call.request.queryParameters["priority"]
            val priority =
                priorityParam?.let {
                    try {
                        TaskPriority.valueOf(it.uppercase())
                    } catch (e: IllegalArgumentException) {
                        null
                    }
                }

            val tasks =
                service.listTasks(
                    status = status,
                    assignee = assignee,
                    tag = tag,
                    priority = priority,
                )
            call.respond(tasks)
        }

        post {
            val req = call.receive<CreateTaskRequest>()
            val created =
                service.createTask(
                    title = req.title,
                    description = req.description,
                    priority = req.priority,
                    assignee = req.assignee,
                    tags = req.tags,
                    branch = req.branch,
                    githubRepo = req.githubRepo,
                    githubIssueUrl = req.githubIssueUrl,
                    status = req.status,
                    operator = req.operator,
                )
            call.respond(HttpStatusCode.Created, created)
        }

        post("/claim") {
            val req = call.receive<ClaimTaskRequest>()
            val claimed =
                service.claimNextTask(
                    fromStatus = req.fromStatus,
                    toStatus = req.toStatus,
                    agentName = req.agentName,
                    tag = req.tag,
                )
            if (claimed != null) {
                call.respond(HttpStatusCode.OK, claimed)
            } else {
                call.respond(HttpStatusCode.NoContent, "")
            }
        }

        get("/{id}") {
            val id =
                call.parameters["id"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid task ID: ${call.parameters["id"]}")
            val task = service.getTask(id)
            call.respond(task)
        }

        put("/{id}") {
            val id =
                call.parameters["id"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid task ID: ${call.parameters["id"]}")
            val req = call.receive<UpdateTaskRequest>()
            val updated =
                service.updateTask(
                    taskId = id,
                    title = req.title,
                    description = req.description,
                    priority = req.priority,
                    assignee = req.assignee,
                    tags = req.tags,
                    branch = req.branch,
                    githubRepo = req.githubRepo,
                    githubIssueUrl = req.githubIssueUrl,
                    githubPrUrl = req.githubPrUrl,
                    operator = req.operator,
                    comment = req.comment,
                )
            call.respond(HttpStatusCode.OK, updated)
        }

        delete("/{id}") {
            val id =
                call.parameters["id"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid task ID: ${call.parameters["id"]}")
            if (service.getTaskOrNull(id) == null) {
                throw TaskNotFoundException(id)
            }
            val deleted = service.deleteTask(id)
            if (deleted) {
                call.respond(HttpStatusCode.OK, MessageResponse("Task $id deleted successfully"))
            } else {
                throw TaskNotFoundException(id)
            }
        }

        post("/{id}/move") {
            val id =
                call.parameters["id"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid task ID: ${call.parameters["id"]}")
            val req = call.receive<MoveTaskRequest>()
            val moved =
                service.moveTask(
                    taskId = id,
                    toStatus = req.toStatus,
                    operator = req.operator,
                    comment = req.comment,
                    prUrl = req.prUrl,
                    assignee = req.assignee,
                )
            call.respond(HttpStatusCode.OK, moved)
        }

        post("/{id}/release") {
            val id =
                call.parameters["id"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid task ID: ${call.parameters["id"]}")
            val req = call.receive<ReleaseTaskRequest>()
            val released =
                service.releaseTask(
                    taskId = id,
                    operator = req.operator,
                    targetStatus = req.targetStatus,
                    comment = req.comment,
                )
            call.respond(HttpStatusCode.OK, released)
        }

        get("/{id}/logs") {
            val id =
                call.parameters["id"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid task ID: ${call.parameters["id"]}")
            val logs = service.getTaskLogs(id)
            call.respond(logs)
        }

        post("/{id}/logs") {
            val id =
                call.parameters["id"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid task ID: ${call.parameters["id"]}")
            val req = call.receive<AddCommentRequest>()
            val entry =
                service.addComment(
                    taskId = id,
                    operator = req.operator,
                    comment = req.comment,
                    prUrl = req.prUrl,
                    commitHash = req.commitHash,
                )
            call.respond(HttpStatusCode.Created, entry)
        }
    }
}
