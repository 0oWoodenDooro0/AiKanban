package aikanban.cli.prompt

interface InteractivePrompter {
    fun isInteractive(): Boolean

    fun prompt(
        message: String,
        default: String? = null,
    ): String?

    fun promptChoice(
        message: String,
        choices: List<String>,
        default: String? = null,
    ): String?

    fun confirm(
        message: String,
        default: Boolean = true,
    ): Boolean
}
