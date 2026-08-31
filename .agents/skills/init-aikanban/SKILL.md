---
name: init-aikanban
description: >-
  Standard workflow to inspect any repository or codebase and automatically initialize, customize, and verify
  its AiKanban configuration (.aikanban.json). Intelligently analyzes tech stack, build tools, test frameworks,
  linters, formatters, Git provider, remote repository, and base branch, tailoring quality verification commands,
  lifecycle hooks (pre-commit, pre-submit-pr, review hooks), custom workflows (build, release, install), and workflow settings.
  Use this skill whenever asked to initialize, configure, setup, or customize AiKanban config for a project.
---

# AiKanban Project Configuration Initialization Skill

This skill defines the autonomous, intelligent procedure for inspecting any project or codebase and generating a tailored, fully verified **AiKanban** configuration file (`.aikanban.json`).

AiKanban uses `.aikanban.json` at the root of a project to configure Git/VCS provider integration, quality verification test suites, automated lifecycle hooks (e.g. format on commit, test before PR), custom workflows, and board column lifecycles.

---

## Workflow Overview

```
[1. VCS & Git Provider Detection]
               │
               ▼
[2. Tech Stack & Build Tool Analysis]
  • Test runner detection
  • Linter & Typechecker detection
  • Code Formatter detection
  • Build & Packaging task detection
               │
               ▼
[3. Synthesize Tailored `.aikanban.json`]
  • provider & repo & defaultBaseBranch & branchPrefix
  • verify commands
  • hooks (pre-commit, pre-submit-pr, review hooks)
  • workflows (build, release, install, custom tasks)
  • workflow lifecycle options
               │
               ▼
[4. Write Configuration & Dynamic Verification]
  • Write `.aikanban.json`
  • Run `aikanban config show`
  • Run `aikanban workflow verify`
               │
               ▼
[5. Report Summary & Verification Status to User]
```

---

## Step-by-Step Execution Guide

### Step 1: Git & VCS Provider Discovery

Inspect the Git environment to determine VCS provider, remote repository, and default base branch:

1. **Check if Git Repository**:
   ```bash
   git rev-parse --is-inside-work-tree
   ```
2. **Detect Remote URL & Repository Slug**:
   ```bash
   REMOTE_URL=$(git remote get-url origin 2>/dev/null || echo "")
   ```
   - **GitHub Detection**:
     - If `REMOTE_URL` matches `*github.com*` (e.g. `git@github.com:owner/repo.git` or `https://github.com/owner/repo.git`):
       - `provider`: `"github"`
       - `repo`: `"owner/repo"` (strip protocol, domain, and trailing `.git`)
   - **Local / Other VCS**:
     - If no remote or not GitHub:
       - `provider`: `"local-git"`
       - `repo`: `null` (or project folder name)
3. **Detect Default Base Branch**:
   ```bash
   BASE_BRANCH=$(git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null | sed 's@^refs/remotes/origin/@@' || echo "")
   if [ -z "$BASE_BRANCH" ]; then
     if git show-ref --verify --quiet refs/heads/main; then
       BASE_BRANCH="main"
     elif git show-ref --verify --quiet refs/heads/master; then
       BASE_BRANCH="master"
     else
       BASE_BRANCH="main"
     fi
   fi
   ```
4. **Determine Branch Prefix**:
   - Standard default: `"feature/"`

---

### Step 2: Tech Stack & Build Tool Analysis

Scan root and directory structure for configuration files, build scripts, package managers, and test/lint tooling across major ecosystems:

#### 1. JVM (Kotlin / Java / Gradle / Maven)
- **Indicators**: `build.gradle.kts`, `build.gradle`, `gradlew`, `pom.xml`, `mvnw`, `settings.gradle.kts`
- **Gradle Detection**:
  - Wrapper: Use `./gradlew` if `gradlew` exists, else `gradle`
  - Test command: `./gradlew test` (or `./gradlew check`)
  - Linter check:
    - If `ktlint` plugin present: `./gradlew ktlintCheck`
    - If `spotless` plugin present: `./gradlew spotlessCheck`
    - If `checkstyle` plugin present: `./gradlew checkstyleMain`
    - If `detekt` plugin present: `./gradlew detekt`
  - Formatter (for `pre-commit`):
    - If `ktlint` present: `./gradlew ktlintFormat`
    - If `spotless` present: `./gradlew spotlessApply`
  - Build/Release workflows:
    - Shadow jar / standalone binary: `./gradlew buildExecutable` or `./gradlew shadowJar` or `./gradlew assemble`
    - CLI installation: `./gradlew installCli` (if task exists)
- **Maven Detection**:
  - Wrapper: Use `./mvnw` if `mvnw` exists, else `mvn`
  - Test: `./mvnw test`
  - Lint: `./mvnw spotless:check` or `./mvnw checkstyle:check`
  - Formatter: `./mvnw spotless:apply`
  - Build: `./mvnw package`

#### 2. Node.js / TypeScript / JavaScript (Frontend & Backend)
- **Indicators**: `package.json`, `pnpm-lock.yaml`, `yarn.lock`, `package-lock.json`, `bun.lockb`
- **Package Manager**:
  - `pnpm-lock.yaml` -> `pnpm`
  - `yarn.lock` -> `yarn`
  - `bun.lockb` -> `bun`
  - `package-lock.json` / default -> `npm`
- **Inspect `scripts` in `package.json`**:
  - Test command: `<pkg-mgr> test` (or `<pkg-mgr> run test:unit`, `<pkg-mgr> run test`)
  - Typecheck: `<pkg-mgr> run typecheck` or `<pkg-mgr> run type-check` (if script exists) or `npx tsc --noEmit`
  - Lint check: `<pkg-mgr> run lint`
  - Formatter (for `pre-commit`): `<pkg-mgr> run format` or `<pkg-mgr> run lint:fix` or `npx prettier --write .`
  - Build/Release workflows: `<pkg-mgr> run build`

#### 3. Python
- **Indicators**: `pyproject.toml`, `setup.py`, `Pipfile`, `requirements.txt`, `poetry.lock`, `uv.lock`
- **Environment Manager**:
  - `uv.lock` -> `uv run`
  - `poetry.lock` -> `poetry run`
  - `Pipfile` -> `pipenv run`
  - Virtualenv / system -> direct binary (`pytest`, `ruff`, etc.)
- **Tooling Detection**:
  - Test: `pytest` or `python -m unittest`
  - Linter: `ruff check` or `flake8` or `mypy .` / `pyright`
  - Formatter (for `pre-commit`): `ruff format` or `black .`
  - Build: `poetry build` or `flit build` or `python -m build`

#### 4. Go
- **Indicators**: `go.mod`, `Makefile`
- **Tooling**:
  - Test: `go test ./...`
  - Linter: `golangci-lint run` (if config exists or tool available)
  - Formatter: `gofmt -w .` or `goimports -w .`
  - Build: `go build ./...`

#### 5. Rust
- **Indicators**: `Cargo.toml`
- **Tooling**:
  - Test: `cargo test`
  - Linter: `cargo clippy -- -D warnings`, `cargo fmt --check`
  - Formatter (for `pre-commit`): `cargo fmt`
  - Build/Release: `cargo build --release`

#### 6. Generic / Makefile / Polyglot / Monorepos
- **Indicators**: `Makefile`, `turbo.json`, `nx.json`
- Inspect `Makefile` for standard targets: `test`, `lint`, `check`, `fmt`, `build`, `release`.
- For Turborepo / Nx: `turbo run test lint build` / `nx run-many -t test,lint`.

---

### Step 3: Synthesize Tailored `.aikanban.json`

Assemble the JSON configuration adhering to the following schema:

```json
{
  "provider": "<github | local-git>",
  "defaultBaseBranch": "<main | master | develop>",
  "repo": "<owner/repo or omit if local-git>",
  "branchPrefix": "feature/",
  "verify": [
    "<Primary Unit Test Command>",
    "<Primary Linter / Typecheck Command>"
  ],
  "hooks": {
    "pre-commit": [
      "<Code Formatter or Fast Linter Fix Command>"
    ],
    "pre-submit-pr": [
      "<Primary Unit Test Command>",
      "<Primary Linter / Typecheck Command>"
    ],
    "pre-complete-review": [
      "<Primary Unit Test Command>"
    ]
  },
  "workflows": {
    "build": {
      "description": "Build project output artifacts",
      "steps": [
        "<Build Command>"
      ]
    },
    "release": {
      "description": "Run full test verification and build release distribution",
      "steps": [
        "<Primary Unit Test Command>",
        "<Primary Linter / Typecheck Command>",
        "<Build Command>"
      ]
    }
  },
  "workflow": {
    "mergeMethod": "squash",
    "deleteBranchOnMerge": true,
    "requestColumn": "REQUEST",
    "doneColumn": "DONE",
    "reviewColumn": "REVIEW"
  }
}
```

#### Selection Rules for Hooks & Verification:
1. **`verify`**: Keep it fast and comprehensive. Include unit tests, static code analysis/linters, and type checkers. Exclude slow end-to-end or flaky browser tests unless specifically desired.
2. **`pre-commit`**: Focus on automated code formatting (e.g. `ktlintFormat`, `ruff format`, `prettier --write`, `cargo fmt`) so staged changes are consistently styled.
3. **`pre-submit-pr`**: Must run the full verification suite (`verify` commands) to prevent broken PRs from being submitted.
4. **`workflows`**: Create practical composite shortcuts for common developer operations (`build`, `release`, `install`, `test`).

---

### Step 4: Write Configuration & Dynamic Verification

1. **Write `.aikanban.json`**:
   Write the generated JSON to `.aikanban.json` in the project root directory.

2. **Verify Configuration Parsing**:
   Run:
   ```bash
   aikanban config show
   ```
   Verify that the configuration is parsed correctly without syntax errors and that all hooks, verify commands, and workflows are displayed.

3. **Verify Quality Commands Execution**:
   Run:
   ```bash
   aikanban --json workflow verify
   ```
   Confirm that all commands in the `verify` list execute successfully. If any command fails due to missing arguments or incorrect flags, tune the command in `.aikanban.json` and re-verify.

---

### Step 5: Report Summary to User

Present a clear and concise summary to the user containing:
- **Detected Project Attributes**: Detected language/framework, build tool, Git remote, and default branch.
- **Generated `.aikanban.json`**: Show the final JSON configuration.
- **Verification Results**: Confirm that `aikanban config show` and `aikanban workflow verify` passed.
- **Available Commands**: Briefly list useful commands enabled by this config (e.g. `aikanban workflow verify`, `aikanban workflow run build`, `aikanban workflow submit-pr`).

---

## Example Configurations

### Example 1: Kotlin / Gradle Multiplatform or CLI Project
```json
{
  "provider": "github",
  "defaultBaseBranch": "main",
  "repo": "owner/repo",
  "branchPrefix": "feature/",
  "verify": [
    "./gradlew test",
    "./gradlew ktlintCheck"
  ],
  "hooks": {
    "pre-commit": [
      "./gradlew ktlintFormat"
    ],
    "pre-submit-pr": [
      "./gradlew test ktlintCheck"
    ]
  },
  "workflows": {
    "build": {
      "description": "Build executable binary or distribution",
      "steps": [
        "./gradlew buildExecutable"
      ]
    },
    "release": {
      "description": "Run test checks and build release binaries",
      "steps": [
        "./gradlew test ktlintCheck",
        "./gradlew buildExecutable"
      ]
    }
  }
}
```

### Example 2: TypeScript / Node.js / React Project
```json
{
  "provider": "github",
  "defaultBaseBranch": "main",
  "repo": "organization/web-app",
  "branchPrefix": "feature/",
  "verify": [
    "pnpm test",
    "pnpm run lint",
    "pnpm run typecheck"
  ],
  "hooks": {
    "pre-commit": [
      "pnpm run format"
    ],
    "pre-submit-pr": [
      "pnpm test",
      "pnpm run lint",
      "pnpm run typecheck"
    ]
  },
  "workflows": {
    "build": {
      "description": "Build production bundle",
      "steps": [
        "pnpm run build"
      ]
    }
  }
}
```

### Example 3: Python (FastAPI / Poetry / Ruff)
```json
{
  "provider": "github",
  "defaultBaseBranch": "main",
  "repo": "myorg/api-service",
  "branchPrefix": "feature/",
  "verify": [
    "poetry run pytest",
    "poetry run ruff check .",
    "poetry run mypy ."
  ],
  "hooks": {
    "pre-commit": [
      "poetry run ruff format ."
    ],
    "pre-submit-pr": [
      "poetry run pytest",
      "poetry run ruff check ."
    ]
  },
  "workflows": {
    "build": {
      "description": "Build wheel package",
      "steps": [
        "poetry build"
      ]
    }
  }
}
```

### Example 4: Rust Project
```json
{
  "provider": "github",
  "defaultBaseBranch": "main",
  "repo": "rust-dev/cli-tool",
  "branchPrefix": "feature/",
  "verify": [
    "cargo test",
    "cargo clippy -- -D warnings",
    "cargo fmt --check"
  ],
  "hooks": {
    "pre-commit": [
      "cargo fmt"
    ],
    "pre-submit-pr": [
      "cargo test",
      "cargo clippy -- -D warnings"
    ]
  },
  "workflows": {
    "build": {
      "description": "Build release binary",
      "steps": [
        "cargo build --release"
      ]
    }
  }
}
```
