package aikanban.github.model

import kotlinx.serialization.Serializable

@Serializable
enum class GitHubResourceType {
    ISSUE,
    PULL_REQUEST,
    REPOSITORY,
}

@Serializable
sealed interface GitHubResource {
    val owner: String
    val repo: String
    val fullRepo: String get() = "$owner/$repo"
    val canonicalUrl: String
    val type: GitHubResourceType

    @Serializable
    data class Issue(
        override val owner: String,
        override val repo: String,
        val number: Int,
    ) : GitHubResource {
        override val canonicalUrl: String get() = "https://github.com/$owner/$repo/issues/$number"
        override val type: GitHubResourceType get() = GitHubResourceType.ISSUE
    }

    @Serializable
    data class PullRequest(
        override val owner: String,
        override val repo: String,
        val number: Int,
    ) : GitHubResource {
        override val canonicalUrl: String get() = "https://github.com/$owner/$repo/pull/$number"
        override val type: GitHubResourceType get() = GitHubResourceType.PULL_REQUEST
    }

    @Serializable
    data class Repository(
        override val owner: String,
        override val repo: String,
    ) : GitHubResource {
        override val canonicalUrl: String get() = "https://github.com/$owner/$repo"
        override val type: GitHubResourceType get() = GitHubResourceType.REPOSITORY
    }
}
