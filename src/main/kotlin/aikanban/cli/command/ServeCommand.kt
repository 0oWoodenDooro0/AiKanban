package aikanban.cli.command

import aikanban.api.startKanbanServer
import aikanban.cli.CliContext
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int

class ServeCommand : CliktCommand(name = "serve") {
    override fun help(context: Context): String = "Start the embedded Ktor REST API and real-time SSE server"

    private val cliContext by requireObject<CliContext>()

    private val port by option("-p", "--port", help = "Port to listen on", envvar = "AIKANBAN_PORT")
        .int()
        .default(8080)

    private val host by option("-h", "--host", help = "Host address to bind to", envvar = "AIKANBAN_HOST")
        .default("0.0.0.0")

    override fun run() {
        cliContext.terminal.println("Starting AiKanban REST & SSE server on http://$host:$port...")
        cliContext.terminal.println("Press Ctrl+C to stop.")
        startKanbanServer(port = port, host = host, service = cliContext.service, wait = true)
    }
}
