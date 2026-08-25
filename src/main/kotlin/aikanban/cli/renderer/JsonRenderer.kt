package aikanban.cli.renderer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object JsonRenderer {
    val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    inline fun <reified T> render(value: T): String {
        return json.encodeToString(value)
    }

    fun renderError(message: String): String {
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return """{"error": "$escaped"}"""
    }
}
