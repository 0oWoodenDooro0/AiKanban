---
name: aikanban
description: >-
  Command reference and usage guide for interacting with the local-first AiKanban CLI board.
  Use this skill whenever an AI agent needs to query, create, update, claim, move, or log tasks on the Kanban board, or sync GitHub issues.
---

# AiKanban CLI Reference

This skill provides a concise command reference for AI agents operating the **AiKanban** CLI board.

---

## Global Options

All `aikanban` commands accept the following global options:

| Option | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `--json` | - | `false` | Output responses in machine-readable JSON format (recommended for AI agents). |
| `--db <path>` | `AIKANBAN_DB` | `aikanban.db` | Path to the SQLite database file. |
| `--generate-completion <shell>` | - | - | Generate shell completion script (`bash`, `zsh`, `fish`). |

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

**Examples:**
```bash
# List all tasks
aikanban --json list

# Filter tasks in progress assigned to an agent
aikanban --json list -s IN_PROGRESS -a agent-1

# Filter high priority tasks with a specific tag
aikanban --json list -p HIGH -t backend
```

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
- `-t, --tag <TAG>`: Task tag (repeatable or comma-separated, e.g. `-t bug,backend`).
- `-s, --status <STATUS>`: Initial board column status (default: `TODO`).
- `--repo <OWNER/REPO>`: Associated GitHub repository.
- `--issue <URL>`: Associated GitHub Issue URL.
- `-o, --operator <NAME>`: Operator identifier (default: `cli`).

**Example:**
```bash
aikanban --json add "Implement user authentication" \
  -d "Add JWT token refresh endpoint" \
  -p HIGH \
  -t auth,backend \
  --issue "https://github.com/owner/repo/issues/42" \
  -o agent-1
```

---

### 3. `show` - Inspect Task Details
Display full metadata, description, and chronological audit history of a task.

```bash
aikanban --json show <TASK_ID>
```

**Example:**
```bash
aikanban --json show 42
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
- `--pr <URL>`: Associated GitHub Pull Request URL.
- `-a, --assignee <NAME>`: Update assignee during the transition.

**Status Columns (Default):** `TODO`, `IN_PROGRESS`, `REVIEW`, `REQUEST`, `PENDING`, `REOPEN`, `DONE`.

**Example:**
```bash
aikanban --json move 42 REVIEW -o agent-1 -c "Completed implementation and passed tests" --pr "https://github.com/owner/repo/pull/50"
```

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

**Example:**
```bash
aikanban --json claim agent-1 --from TODO --to IN_PROGRESS --tag backend
```

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
- `--pr <URL>`: Associated GitHub Pull Request URL.

**Examples:**
```bash
# Append progress comment with commit hash
aikanban --json log 42 -m "Implemented core token validation" --commit "a1b2c3d" -o agent-1

# View all historical logs for task 42
aikanban --json log 42
```

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
- `--repo <OWNER/REPO>`: New GitHub repository.
- `--issue <URL>`: New GitHub Issue URL.
- `--pr <URL>`: New GitHub PR URL.
- `-o, --operator <NAME>`: Operator identifier (default: `cli`).
- `-c, --comment <TEXT>`: Optional log comment explaining the update.

**Example:**
```bash
aikanban --json update 42 --priority URGENT -t auth,critical -o agent-1 -c "Raised priority due to release blocker"
```

---

### 8. `column` - Manage Board Columns
List, create, update, or delete Kanban columns.

```bash
aikanban --json column [subcommand]
```

**Subcommands:**
- `column list`: List all board columns with their IDs, order, and terminal flags.
  ```bash
  aikanban --json column list
  ```
- `column add <ID> <NAME> [options]`: Add a new board column.
  - `-o, --order <INT>`: Display order index (default: `0`).
  - `-c, --color <HEX>`: Hex color code (e.g. `#3B82F6`, default: `#6B7280`).
  - `-t, --terminal`: Mark column as terminal/completion state.
  ```bash
  aikanban --json column add QA "Quality Assurance" -o 3 -c "#EC4899"
  ```
- `column update <ID> [options]`: Update an existing column.
  - `-n, --name <NAME>`: New display name.
  - `-o, --order <INT>`: New display order index.
  - `-c, --color <HEX>`: New hex color code.
  - `-t, --terminal <true/false>`: Set terminal/completion status.
  ```bash
  aikanban --json column update QA -n "Quality & Staging" -c "#F43F5E"
  ```
- `column delete <ID>`: Delete an empty column.
  ```bash
  aikanban --json column delete QA
  ```

---

### 9. `sync-github` - Sync GitHub Issues / PRs
Import and synchronize GitHub issues or a specific issue/PR URL into Kanban tasks.

```bash
aikanban --json sync-github [REPO_OR_URL] [options]
```

**Options:**
- `--url <URL>`: Specific GitHub issue or pull request URL to sync.
- `-s, --state <STATE>`: Issue state filter (`open`, `closed`, `all`, default: `open`).
- `-t, --tag <LABEL>`: Filter by GitHub label (repeatable or comma-separated).
- `--include-prs`: Include pull requests in synchronization (default: `false`).
- `-c, --column <STATUS>`: Target Kanban column for open issues (default: `TODO`).
- `--token <TOKEN>`: GitHub personal access token (or env `GITHUB_TOKEN`).
- `--dry-run`: Preview synchronization without writing to the database.
- `-o, --operator <NAME>`: Operator identifier (default: `cli-github-sync`).

**Examples:**
```bash
# Sync all open issues from a repository
aikanban --json sync-github owner/repo -s open --column TODO

# Sync a specific issue by URL
aikanban --json sync-github --url "https://github.com/owner/repo/issues/42"

# Preview sync without saving to database
aikanban --json sync-github owner/repo --dry-run
```

---

### 10. `serve` - Start Web Dashboard & API Server
Start the embedded Ktor Web Kanban dashboard, REST API, and real-time SSE server.

```bash
aikanban serve [options]
```

**Options:**
- `-p, --port <PORT>`: Port to listen on (default: `8080`, env `AIKANBAN_PORT`).
- `-h, --host <HOST>`: Host address to bind to (default: `0.0.0.0`, env `AIKANBAN_HOST`).
