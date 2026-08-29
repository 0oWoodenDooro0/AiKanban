package aikanban.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AiKanbanConfig(
    val provider: String = "local-git",
    val defaultBaseBranch: String = "main",
    val repo: String? = null,
    val branchPrefix: String = "feature/",
    val token: String? = null,
)

object AiKanbanConfigLoader {
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

    private val CONFIG_FILENAMES = listOf(".aikanban.json", "aikanban.config.json")

    fun load(startDir: File = File(".")): AiKanbanConfig {
        var current: File? = startDir.absoluteFile
        while (current != null) {
            for (filename in CONFIG_FILENAMES) {
                val candidate = File(current, filename)
                if (candidate.isFile && candidate.canRead()) {
                    return try {
                        json.decodeFromString<AiKanbanConfig>(candidate.readText())
                    } catch (e: Exception) {
                        AiKanbanConfig()
                    }
                }
            }
            current = current.parentFile
        }
        return AiKanbanConfig()
    }

    fun save(
        config: AiKanbanConfig,
        dir: File = File("."),
    ): File {
        val target = File(dir, ".aikanban.json")
        target.writeText(json.encodeToString(config))
        return target
    }
}
