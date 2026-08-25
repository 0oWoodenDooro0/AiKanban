package aikanban.model

import kotlinx.serialization.Serializable

@Serializable
enum class TaskPriority(val level: Int) {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    URGENT(4),
}
