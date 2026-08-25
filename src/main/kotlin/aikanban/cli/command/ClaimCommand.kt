package aikanban.cli.command

import aikanban.cli.CliContext
import aikanban.cli.renderer.HumanRenderer
import aikanban.cli.renderer.JsonRenderer
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.yellow

class ClaimCommand : CliktCommand(name = "claim") {
    override fun help(context: Context): String = "Claim the next highest-priority task for an agent"

    private val cliContext by requireObject<CliContext>()

    private val agent by argument("agent", help = "Agent or developer identifier")
    private val from by option("-f", "--from", help = "Source status column to claim from").default("TODO")
    private val to by option("-t", "--to", help = "Target status column to transition to").default("IN_PROGRESS")
    private val tag by option("--tag", help = "Optional tag filter to match")
    private val json by option("--json", help = "Output in machine-readable JSON format").flag(default = false)

    override fun run() {
        val isJson = json || cliContext.jsonOutput
        val task =
            cliContext.service.claimNextTask(
                fromStatus = from,
                toStatus = to,
                agentName = agent,
                tag = tag,
            )

        if (isJson) {
            if (task != null) {
                println(JsonRenderer.render(task))
            } else {
                println("null")
            }
        } else {
            if (task != null) {
                cliContext.terminal.println(
                    green(
                        "✓ Agent @$agent claimed Task #${task.id}: \"${task.title}\" (${HumanRenderer.formatStatus(
                            from,
                        )} -> ${HumanRenderer.formatStatus(to)})",
                    ),
                )
            } else {
                val tagInfo = if (tag != null) " with tag '#$tag'" else ""
                cliContext.terminal.println(yellow("ℹ No available tasks to claim in '$from'$tagInfo."))
            }
        }
    }
}
