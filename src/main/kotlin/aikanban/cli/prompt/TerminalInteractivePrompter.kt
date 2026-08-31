package aikanban.cli.prompt

import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.yellow
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal

class TerminalInteractivePrompter(
    private val terminal: Terminal = Terminal(),
    private val inputReader: () -> String? = { readlnOrNull() },
    private val isInteractiveFn: () -> Boolean = {
        System.console() != null || System.getenv("AIKANBAN_INTERACTIVE") == "true"
    },
) : InteractivePrompter {
    override fun isInteractive(): Boolean = isInteractiveFn()

    override fun prompt(
        message: String,
        default: String?,
    ): String? {
        val defaultSuffix = if (default != null) " [${gray(default)}]" else ""
        terminal.print(bold(cyan("? ")) + message + defaultSuffix + ": ")
        val input = inputReader()?.trim()
        return if (input.isNullOrBlank()) default else input
    }

    override fun promptChoice(
        message: String,
        choices: List<String>,
        default: String?,
    ): String? {
        val choicesFormatted = choices.joinToString("/")
        val defaultSuffix = if (default != null) " [${gray(default)}]" else ""
        terminal.print(bold(cyan("? ")) + message + " (${yellow(choicesFormatted)})" + defaultSuffix + ": ")
        val input = inputReader()?.trim()
        if (input.isNullOrBlank()) return default ?: choices.firstOrNull()

        val matched = choices.firstOrNull { it.equals(input, ignoreCase = true) }
        return matched ?: default ?: choices.firstOrNull()
    }

    override fun confirm(
        message: String,
        default: Boolean,
    ): Boolean {
        val hint = if (default) "Y/n" else "y/N"
        terminal.print(bold(cyan("? ")) + message + " [${yellow(hint)}]: ")
        val input = inputReader()?.trim()
        if (input.isNullOrBlank()) return default
        return when (input.lowercase()) {
            "y", "yes", "true", "1" -> true
            "n", "no", "false", "0" -> false
            else -> default
        }
    }
}
