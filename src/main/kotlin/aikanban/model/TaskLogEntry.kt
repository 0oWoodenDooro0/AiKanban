package aikanban.model

import kotlinx.serialization.Serializable

@Serializable
data class TaskLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val operator: String,
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val comment: String,
    val prUrl: String? = null,
    val commitHash: String? = null
)
