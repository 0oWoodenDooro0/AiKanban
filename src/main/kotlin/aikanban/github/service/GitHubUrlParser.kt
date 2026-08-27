package aikanban.github.service

import aikanban.github.model.GitHubResource

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
            """^(?:https?://)?(?:www\.)?github\.com/([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+?)(?:\.git)?(?:/.*)?$""",
            RegexOption.IGNORE_CASE,
        )

    private val SHORT_REPO_REGEX =
        Regex(
            """^([a-zA-Z0-9_.-]+)/([a-zA-Z0-9_.-]+?)(?:\.git)?$""",
        )

    fun parse(input: String): GitHubResource? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        // Remove query parameters and hash fragment
        val cleanUrl = trimmed.substringBefore('?').substringBefore('#')

        // 1. Check Issue URL
        ISSUE_REGEX.matchEntire(cleanUrl)?.let { match ->
            val (owner, repo, numberStr) = match.destructured
            val cleanRepo = repo.removeSuffix(".git")
            val number = numberStr.toIntOrNull() ?: return null
            return GitHubResource.Issue(owner = owner, repo = cleanRepo, number = number)
        }

        // 2. Check PR URL
        PR_REGEX.matchEntire(cleanUrl)?.let { match ->
            val (owner, repo, numberStr) = match.destructured
            val cleanRepo = repo.removeSuffix(".git")
            val number = numberStr.toIntOrNull() ?: return null
            return GitHubResource.PullRequest(owner = owner, repo = cleanRepo, number = number)
        }

        // 3. Check full GitHub Repo URL
        REPO_URL_REGEX.matchEntire(cleanUrl)?.let { match ->
            val (owner, repo) = match.destructured
            val cleanRepo = repo.removeSuffix(".git")
            if (owner.equals("issues", ignoreCase = true) || owner.equals("pull", ignoreCase = true)) {
                return null
            }
            if (cleanRepo.isNotBlank()) {
                return GitHubResource.Repository(owner = owner, repo = cleanRepo)
            }
        }

        // 4. Check short "owner/repo" form
        SHORT_REPO_REGEX.matchEntire(cleanUrl)?.let { match ->
            val (owner, repo) = match.destructured
            val cleanRepo = repo.removeSuffix(".git")
            return GitHubResource.Repository(owner = owner, repo = cleanRepo)
        }

        return null
    }

    fun parseIssue(input: String): GitHubResource.Issue? {
        return parse(input) as? GitHubResource.Issue
    }

    fun parsePullRequest(input: String): GitHubResource.PullRequest? {
        return parse(input) as? GitHubResource.PullRequest
    }

    fun parseRepository(input: String): GitHubResource.Repository? {
        return when (val resource = parse(input)) {
            is GitHubResource.Repository -> resource
            is GitHubResource.Issue -> GitHubResource.Repository(resource.owner, resource.repo)
            is GitHubResource.PullRequest -> GitHubResource.Repository(resource.owner, resource.repo)
            null -> null
        }
    }
}
