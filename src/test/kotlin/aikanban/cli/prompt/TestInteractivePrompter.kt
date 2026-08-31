package aikanban.cli.prompt

class TestInteractivePrompter(
    var interactive: Boolean = true,
    val promptResponses: MutableList<String?> = mutableListOf(),
    val choiceResponses: MutableList<String?> = mutableListOf(),
    val confirmResponses: MutableList<Boolean> = mutableListOf(),
) : InteractivePrompter {
    val recordedPrompts = mutableListOf<String>()
    val recordedChoices = mutableListOf<Pair<String, List<String>>>()
    val recordedConfirms = mutableListOf<String>()

    override fun isInteractive(): Boolean = interactive

    override fun prompt(
        message: String,
        default: String?,
    ): String? {
        recordedPrompts.add(message)
        return if (promptResponses.isNotEmpty()) promptResponses.removeAt(0) else default
    }

    override fun promptChoice(
        message: String,
        choices: List<String>,
        default: String?,
    ): String? {
        recordedChoices.add(message to choices)
        return if (choiceResponses.isNotEmpty()) choiceResponses.removeAt(0) else default
    }

    override fun confirm(
        message: String,
        default: Boolean,
    ): Boolean {
        recordedConfirms.add(message)
        return if (confirmResponses.isNotEmpty()) confirmResponses.removeAt(0) else default
    }
}
