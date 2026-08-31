package aikanban.cli.renderer

import aikanban.model.BoardColumn
import aikanban.model.Task
import aikanban.model.TaskLogEntry
import aikanban.model.TaskPriority
import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.rendering.TextColors.blue
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.magenta
import com.github.ajalt.mordant.rendering.TextColors.red
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.TextStyles.dim
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Panel
import com.github.ajalt.mordant.widgets.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object HumanRenderer {
    private val dateFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())

    fun formatStatus(status: String): String {
        return when (status.uppercase()) {
            "TODO" -> gray(status)
            "IN_PROGRESS" -> blue(status)
            "REVIEW" -> magenta(status)
            "REQUEST" -> yellow(status)
            "PENDING" -> dim(status)
            "REOPEN" -> red(bold(status))
            "DONE" -> green(bold(status))
            else -> cyan(status)
        }
    }

    fun formatPriority(priority: TaskPriority): String {
        return when (priority) {
            TaskPriority.LOW -> dim("LOW")
            TaskPriority.MEDIUM -> cyan("MEDIUM")
            TaskPriority.HIGH -> yellow(bold("HIGH"))
            TaskPriority.URGENT -> red(bold("URGENT"))
        }
    }

    private fun formatTime(millis: Long?): String {
        if (millis == null) return "-"
        return dateFormatter.format(Instant.ofEpochMilli(millis))
    }

    fun renderTaskList(
        terminal: Terminal,
        tasks: List<Task>,
        filterDescription: String? = null,
    ) {
        if (tasks.isEmpty()) {
            val msg = if (filterDescription != null) "No tasks found matching criteria ($filterDescription)." else "No tasks found."
            terminal.println(yellow("ℹ $msg"))
            return
        }

        val renderedTable =
            table {
                header {
                    row(
                        bold("ID"),
                        bold("Title"),
                        bold("Status"),
                        bold("Priority"),
                        bold("Assignee"),
                        bold("Tags"),
                        bold("Updated"),
                    )
                }
                body {
                    for (task in tasks) {
                        row(
                            task.id.toString(),
                            task.title,
                            formatStatus(task.status),
                            formatPriority(task.priority),
                            task.assignee?.let { cyan("@$it") } ?: dim("-"),
                            if (task.tags.isEmpty()) dim("-") else task.tags.joinToString(", ") { blue("#$it") },
                            dim(formatTime(task.updatedAt)),
                        )
                    }
                }
            }
        terminal.println(renderedTable)
        terminal.println(dim("Total: ${tasks.size} task(s)"))
    }

    fun renderTaskDetail(
        terminal: Terminal,
        task: Task,
    ) {
        val metaTable =
            table {
                body {
                    row(bold("ID:"), task.id.toString(), bold("Status:"), formatStatus(task.status))
                    row(
                        bold("Priority:"),
                        formatPriority(task.priority),
                        bold("Assignee:"),
                        task.assignee?.let { cyan("@$it") } ?: dim("Unassigned"),
                    )
                    row(
                        bold("Tags:"),
                        if (task.tags.isEmpty()) {
                            dim("None")
                        } else {
                            task.tags.joinToString(", ") {
                                blue("#$it")
                            }
                        },
                        bold("Created:"),
                        dim(formatTime(task.createdAt)),
                    )
                    row(bold("Repo:"), task.githubRepo ?: dim("-"), bold("Updated:"), dim(formatTime(task.updatedAt)))
                    row(bold("Issue:"), task.githubIssueUrl ?: dim("-"), bold("Completed:"), dim(formatTime(task.completedAt)))
                    if (task.branch != null || task.githubPrUrl != null) {
                        row(
                            bold("Branch:"),
                            task.branch?.let { blue(it) } ?: dim("-"),
                            bold("PR:"),
                            task.githubPrUrl ?: dim("-"),
                        )
                    }
                }
            }

        terminal.println(
            Panel(
                content = metaTable,
                title = Text(bold(cyan("Task #${task.id}: ${task.title}"))),
            ),
        )

        terminal.println(bold("\n📝 Description:"))
        if (task.description.isNotBlank()) {
            terminal.println(Markdown(task.description))
        } else {
            terminal.println(dim("  (No description provided)"))
        }

        if (task.logs.isNotEmpty()) {
            terminal.println(bold("\n📜 Activity Logs:"))
            renderTaskLogs(terminal, task.logs)
        }
    }

    fun renderTaskLogs(
        terminal: Terminal,
        logs: List<TaskLogEntry>,
    ) {
        if (logs.isEmpty()) {
            terminal.println(dim("No activity logs recorded."))
            return
        }

        for (log in logs) {
            val transition =
                if (log.fromStatus != null && log.toStatus != null) {
                    " (${formatStatus(log.fromStatus)} -> ${formatStatus(log.toStatus)})"
                } else if (log.toStatus != null) {
                    " (-> ${formatStatus(log.toStatus)})"
                } else {
                    ""
                }
            val meta =
                listOfNotNull(
                    log.prUrl?.let { "PR: $it" },
                    log.commitHash?.let { "Commit: $it" },
                ).joinToString(", ").ifBlank { null }
            val metaStr = if (meta != null) dim(" [$meta]") else ""

            terminal.println("  • ${dim(formatTime(log.timestamp))} ${cyan("@" + log.operator)}$transition: ${log.comment}$metaStr")
        }
    }

    fun renderColumnList(
        terminal: Terminal,
        columns: List<BoardColumn>,
    ) {
        val colTable =
            table {
                header {
                    row(
                        bold("Order"),
                        bold("ID"),
                        bold("Name"),
                        bold("Color"),
                        bold("Terminal"),
                    )
                }
                body {
                    for (col in columns) {
                        row(
                            col.order.toString(),
                            bold(col.id),
                            col.name,
                            col.color,
                            if (col.isTerminal) green("Yes") else dim("No"),
                        )
                    }
                }
            }
        terminal.println(colTable)
    }
}
