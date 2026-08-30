---
name: aikanban
description: >-
  Command reference and usage guide for interacting with the local-first AiKanban CLI board.
  Use this skill whenever an AI agent needs to query, create, update, claim, move, or log tasks on the Kanban board, sync issues, or automate workflows.
---

# AiKanban CLI Reference

This skill provides a concise command reference for AI agents operating the **AiKanban** CLI board.

---

## Global Options & Configuration

All `aikanban` commands accept the following global options:

| Option | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `--json` | - | `false` | Output responses in machine-readable JSON format (recommended for AI agents). |
| `--db <path>` | `AIKANBAN_DB` | `aikanban.db` | Path to the SQLite database file. |
| `--generate-completion <shell>` | - | - | Generate shell completion script (`bash`, `zsh`, `fish`). |

### Project Configuration (`.aikanban.json`)

Repositories can configure AiKanban preferences in `.aikanban.json` or `aikanban.config.json`:

```json
{
  "provider": "local-git",
  "defaultBaseBranch": "main",
  "repo": "owner/repo",
  "branchPrefix": "feature/",
  "token": "optional-token"
}
```

- **Supported Providers**: `local-git` (default, pure offline local Git), `github` (GitHub CLI & API).

---

## Commands & Usage

### 1. `list` - List and Filter Tasks
List tasks on the board with optional filtering.

```bash
aikanban --json list [options]
```

**Options:**
- `-s, --status <STATUS>`: Filter by column status (e.g. `TODO`, `IN_PROGRESS`, `REVIEW`, `DONE`).
- `-a, --assignee <NAME>`: Filter by assigned user or agent name.
- `-t, --tag <TAG>`: Filter by tag name.
- `-p, --priority <PRIORITY>`: Filter by priority (`LOW`, `MEDIUM`, `HIGH`, `URGENT`).

---

### 2. `add` - Create a Task
Create a new task on the kanban board.

```bash
aikanban --json add "<TITLE>" [options]
```

**Options:**
- `-d, --description <TEXT>`: Detailed Markdown task description.
- `-p, --priority <PRIORITY>`: Task priority (`LOW`, `MEDIUM`, `HIGH`, `URGENT`, default: `MEDIUM`).
- `-a, --assignee <NAME>`: Assigned user or agent.
- `-t, --tag <TAG>`: Task tag (repeatable or comma-separated).
- `-s, --status <STATUS>`: Initial board column status (default: `TODO`).
- `--repo <OWNER/REPO>`: Associated repository.
- `--issue <URL>`: Associated Issue URL.
- `-o, --operator <NAME>`: Operator identifier (default: `cli`).

---

### 3. `show` - Inspect Task Details
Display full metadata, description, and chronological audit history of a task.

```bash
aikanban --json show <TASK_ID>
```

---

### 4. `move` - Move Task to Another Column
Transition a task to a target column status.

```bash
aikanban --json move <TASK_ID> <STATUS> [options]
```

**Options:**
- `-o, --operator <NAME>`: Identifier of the operator making the move (default: `cli`).
- `-c, --comment <TEXT>`: Comment or rationale for the status transition.
- `--pr <URL>`: Associated Pull Request URL.
- `-a, --assignee <NAME>`: Update assignee during the transition.

**Status Columns (Default):** `TODO`, `IN_PROGRESS`, `REVIEW`, `REQUEST`, `PENDING`, `REOPEN`, `DONE`.

---

### 5. `claim` - Claim Highest-Priority Task
Atomically claim the next highest-priority task (ordered by `URGENT` > `HIGH` > `MEDIUM` > `LOW`) from a source column and move it to a target column assigned to the agent.

```bash
aikanban --json claim <AGENT_NAME> [options]
```

**Options:**
- `-f, --from <STATUS>`: Source status column to claim from (default: `TODO`).
- `-t, --to <STATUS>`: Target status column to transition to (default: `IN_PROGRESS`).
- `--tag <TAG>`: Optional tag filter to match.

---

### 6. `log` - View or Append Task Logs
View audit logs or append a progress comment, Git commit hash, or PR link to a task.

```bash
aikanban --json log <TASK_ID> [options]
```

**Options:**
- `-m, -c, --comment <TEXT>`: Comment or progress log message to append.
- `-o, --operator <NAME>`: Operator adding the log entry (default: `cli`).
- `--commit <HASH>`: Associated Git commit hash.
- `--pr <URL>`: Associated Pull Request URL.

---

### 7. `update` - Update Task Metadata
Modify task fields and properties.

```bash
aikanban --json update <TASK_ID> [options]
```

**Options:**
- `--title <TEXT>`: New task title.
- `-d, --description <TEXT>`: New task description.
- `-p, --priority <PRIORITY>`: New task priority (`LOW`, `MEDIUM`, `HIGH`, `URGENT`).
- `-a, --assignee <NAME>`: New assignee name.
- `-t, --tag <TAG>`: Replacement tags (repeatable or comma-separated).
- `--repo <OWNER/REPO>`: New repository.
- `--issue <URL>`: New Issue URL.
- `--pr <URL>`: New PR URL.
- `-o, --operator <NAME>`: Operator identifier (default: `cli`).
- `-c, --comment <TEXT>`: Optional log comment explaining the update.

---

### 8. `column` - Manage Board Columns
List, create, update, or delete Kanban columns.

```bash
aikanban --json column [subcommand]
```

**Subcommands:**
- `column list`: List all board columns with their IDs, order, and terminal flags.
- `column add <ID> <NAME> [options]`: Add a new board column (`-o <INT>`, `-c <HEX>`, `-t`).
- `column update <ID> [options]`: Update an existing column (`-n, --name <NAME>`, `-o <INT>`, `-c <HEX>`, `-t <true/false>`).
- `column delete <ID>`: Delete an empty column.

---

### 9. `sync` - Synchronize Issues from VCS Provider
Vendor-neutral synchronization command that imports issues/PRs into Kanban tasks based on configured or specified provider.

```bash
aikanban --json sync [REPO_OR_URL] [options]
```

**Options:**
- `--provider <NAME>`: VCS provider override (`local-git`, `github`).
- `--url <URL>`: Specific issue or pull request URL to sync.
- `-s, --state <STATE>`: Issue state filter (`open`, `closed`, `all`, default: `open`).
- `-t, --tag <LABEL>`: Filter by label (repeatable or comma-separated).
- `--include-prs`: Include pull requests in synchronization (default: `false`).
- `-c, --column <STATUS>`: Target Kanban column for open issues (default: `TODO`).
- `--token <TOKEN>`: Personal access token (or env `GITHUB_TOKEN`).
- `--dry-run`: Preview synchronization without writing to the database.
- `-o, --operator <NAME>`: Operator identifier (default: `cli-sync`).

**Examples:**
```bash
# Sync using default local-git or project config
aikanban --json sync

# Sync open issues from GitHub repository
aikanban --json sync owner/repo --provider github -s open

# Sync specific issue URL
aikanban --json sync --url "https://github.com/owner/repo/issues/42"
```

---

### 10. `workflow` - Composite Development Workflows
High-level commands automating multi-step issue, branch, and PR lifecycles.

#### `workflow start-issue`
Atomically creates an issue (if remote provider), creates a Kanban task in `TODO`, attaches implementation plan, creates & checks out a dedicated Git branch, and logs actions.

```bash
aikanban --json workflow start-issue "<TITLE>" [options]
```

**Options:**
- `-d, --description <TEXT>`: Markdown description.
- `-p, --priority <PRIORITY>`: Priority (`LOW`, `MEDIUM`, `HIGH`, `URGENT`).
- `-t, --tag <TAG>`: Tags.
- `-b, --branch <NAME>`: Dedicated branch name (auto-generated if omitted).
- `--base <BRANCH>`: Base branch (default: `main`).
- `--plan <TEXT_OR_FILE>`: Implementation plan markdown text or file path.
- `-a, --assignee <NAME>`: Assigned user or agent.
- `--provider <NAME>`: VCS provider override (`local-git`, `github`).
- `--dry-run`: Preview workflow actions without writing.

**Example:**
```bash
aikanban --json workflow start-issue "Implement JWT auth" \
  -d "Add token refresh" \
  -p HIGH \
  -t auth,backend \
  --plan "path/to/plan.md" \
  -a agent-1
```

#### `workflow submit-pr`
Atomically pushes the branch to remote, creates a Pull Request via the active provider, links the PR URL, and transitions the Kanban task to `REVIEW`.

```bash
aikanban --json workflow submit-pr <TASK_ID> [options]
```

**Options:**
- `--title <TEXT>`: PR title (defaults to task title).
- `--body <TEXT>`: PR body markdown.
- `--body-file <FILE>`: File containing PR body markdown.
- `--head <BRANCH>`: Head branch (defaults to current Git branch).
- `--base <BRANCH>`: Base branch (default: `main`).
- `--draft`: Open as draft PR.
- `--provider <NAME>`: VCS provider override (`local-git`, `github`).
- `--dry-run`: Preview PR submission without executing.

**Example:**
```bash
aikanban --json workflow submit-pr 42 \
  --body "## Summary\n- Implemented JWT auth\nCloses #42"
```

---

### 11. `serve` - Start Web Dashboard & API Server
Start the embedded Ktor Web Kanban dashboard, REST API, and real-time SSE server.

```bash
aikanban serve [options]
```

**Options:**
- `-p, --port <PORT>`: Port to listen on (default: `8080`, env `AIKANBAN_PORT`).
- `-h, --host <HOST>`: Host address to bind to (default: `0.0.0.0`, env `AIKANBAN_HOST`).
