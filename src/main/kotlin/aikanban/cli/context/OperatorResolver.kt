package aikanban.cli.context

import aikanban.config.AiKanbanConfig
import aikanban.provider.GitCommandRunner
import java.io.File

object OperatorResolver {
    const val DEFAULT_FALLBACK = "workflow"

    fun resolve(
        explicitOperator: String? = null,
        config: AiKanbanConfig? = null,
        gitCommandRunner: GitCommandRunner? = null,
        workingDir: File? = null,
        fallback: String = DEFAULT_FALLBACK,
    ): String {
        // 1. Explicit CLI Option / Argument
        if (!explicitOperator.isNullOrBlank()) {
            return explicitOperator.trim()
        }

        // 2. AiKanban Configuration (.aikanban.json / global config)
        val configOperator = config?.operator?.takeIf { it.isNotBlank() }
        if (configOperator != null) {
            return configOperator.trim()
        }

        // 3. Git config: git config user.name
        val gitUserName =
            try {
                gitCommandRunner?.getUserName(workingDir)?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        if (gitUserName != null) {
            return gitUserName.trim()
        }

        // 4. Fallback Default
        return fallback
    }
}
