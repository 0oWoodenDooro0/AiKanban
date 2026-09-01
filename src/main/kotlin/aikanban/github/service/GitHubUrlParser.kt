package aikanban.github.service

import aikanban.provider.ResolvedResource
import aikanban.provider.ResourceType

object GitHubUrlParser {
    private val ISSUE_REGEX =
        Regex(
            """^(?:https?://)?(?:www\.)?github\.com/([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+)/issues/(\d+)(?:/.*)?$""",
            RegexOption.IGNORE_CASE,
        )

    private val PR_REGEX =
        Regex(
            """^(?:https?://)?(?:www\.)?github\.com/([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+)/pull/(\d+)(?:/.*)?$""",
            RegexOption.IGNORE_CASE,
        )

    private val REPO_URL_REGEX =
        Regex(
            """^(?:(?:https?://)?(?:www\.)?|(?:ssh://)?git@)github\.com[:/]([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+?)(?:\.git)?(?:/.*)?$""",
            RegexOption.IGNORE_CASE,
        )

    private val SHORT_REPO_REGEX =
        Regex(
            """^([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+?)(?:\.git)?$""",
        )

    fun parse(input: String): ResolvedResource? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        // Remove query parameters and hash fragment
        val cleanUrl = trimmed.substringBefore('?').substringBefore('#')

        // 1. Check Issue URL
        ISSUE_REGEX.matchEntire(cleanUrl)?.let { match ->
            val (owner, repo, numberStr) = match.destructured
            val cleanRepo = repo.removeSuffix(".git")
            val number = numberStr.toIntOrNull() ?: return null
            return ResolvedResource(
                provider = "github",
                owner = owner,
                repo = cleanRepo,
                type = ResourceType.ISSUE,
                number = number,
                canonicalUrl = "https://github.com/$owner/$cleanRepo/issues/$number",
            )
        }

        // 2. Check PR URL
        PR_REGEX.matchEntire(cleanUrl)?.let { match ->
            val (owner, repo, numberStr) = match.destructured
            val cleanRepo = repo.removeSuffix(".git")
            val number = numberStr.toIntOrNull() ?: return null
            return ResolvedResource(
                provider = "github",
                owner = owner,
                repo = cleanRepo,
                type = ResourceType.PULL_REQUEST,
                number = number,
                canonicalUrl = "https://github.com/$owner/$cleanRepo/pull/$number",
            )
        }

        // 3. Check full GitHub Repo URL
        REPO_URL_REGEX.matchEntire(cleanUrl)?.let { match ->
            val (owner, repo) = match.destructured
            val cleanRepo = repo.removeSuffix(".git")
            if (owner.equals("issues", ignoreCase = true) || owner.equals("pull", ignoreCase = true)) {
                return null
            }
            if (cleanRepo.isNotBlank()) {
                return ResolvedResource(
                    provider = "github",
                    owner = owner,
                    repo = cleanRepo,
                    type = ResourceType.REPOSITORY,
                    number = null,
                    canonicalUrl = "https://github.com/$owner/$cleanRepo",
                )
            }
        }

        // 4. Check short "owner/repo" form
        SHORT_REPO_REGEX.matchEntire(cleanUrl)?.let { match ->
            val (owner, repo) = match.destructured
            val cleanRepo = repo.removeSuffix(".git")
            return ResolvedResource(
                provider = "github",
                owner = owner,
                repo = cleanRepo,
                type = ResourceType.REPOSITORY,
                number = null,
                canonicalUrl = "https://github.com/$owner/$cleanRepo",
            )
        }

        return null
    }

    fun parseIssue(input: String): ResolvedResource? {
        val resource = parse(input)
        return if (resource?.type == ResourceType.ISSUE) resource else null
    }

    fun parsePullRequest(input: String): ResolvedResource? {
        val resource = parse(input)
        return if (resource?.type == ResourceType.PULL_REQUEST) resource else null
    }

    fun parseRepository(input: String): ResolvedResource? {
        val resource = parse(input) ?: return null
        return when (resource.type) {
            ResourceType.REPOSITORY -> resource
            ResourceType.ISSUE, ResourceType.PULL_REQUEST ->
                ResolvedResource(
                    provider = resource.provider,
                    owner = resource.owner,
                    repo = resource.repo,
                    type = ResourceType.REPOSITORY,
                    number = null,
                    canonicalUrl = "https://github.com/${resource.owner}/${resource.repo}",
                )
        }
    }
}
