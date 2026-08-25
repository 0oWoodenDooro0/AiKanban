package aikanban.cli

import aikanban.service.KanbanService
import com.github.ajalt.mordant.terminal.Terminal

data class CliContext(
    val service: KanbanService,
    val terminal: Terminal = Terminal(),
    val jsonOutput: Boolean = false,
)
