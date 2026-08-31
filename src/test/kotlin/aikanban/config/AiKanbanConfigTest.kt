package aikanban.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiKanbanConfigTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("Should return default config when no config file exists")
    fun testDefaultConfigWhenMissing() {
        val workingDir = tempDir.toFile()
        val config = AiKanbanConfigLoader.load(workingDir, mergeGlobal = false)

        assertEquals("local-git", config.provider)
        assertEquals("main", config.defaultBaseBranch)
        assertEquals("feature/", config.branchPrefix)
        assertNull(config.repo)
        assertNull(config.token)
        assertTrue(config.verify.isEmpty())
        assertTrue(config.hooks.isEmpty())
        assertTrue(config.workflows.isEmpty())
    }

    @Test
    @DisplayName("Should load valid .aikanban.json configuration file with verify, hooks, and workflows")
    fun testLoadConfigFile() {
        val workingDir = tempDir.toFile()
        val configFile = File(workingDir, ".aikanban.json")
        configFile.writeText(
            """
            {
                "provider": "github",
                "defaultBaseBranch": "master",
                "repo": "owner/custom-repo",
                "branchPrefix": "feat/",
                "token": "secret-token",
                "verify": ["./gradlew test", "./gradlew ktlintCheck"],
                "hooks": {
                    "pre-submit-pr": ["./gradlew check"],
                    "pre-commit": ["./gradlew ktlintFormat"]
                },
                "workflows": {
                    "release": {
                        "description": "Release workflow",
                        "steps": ["./gradlew test", "./gradlew buildExecutable"]
                    }
                }
            }
            """.trimIndent(),
        )

        val config = AiKanbanConfigLoader.load(workingDir, mergeGlobal = false)
        assertEquals("github", config.provider)
        assertEquals("master", config.defaultBaseBranch)
        assertEquals("owner/custom-repo", config.repo)
        assertEquals("feat/", config.branchPrefix)
        assertEquals("secret-token", config.token)
        assertEquals(listOf("./gradlew test", "./gradlew ktlintCheck"), config.verify)
        assertEquals(listOf("./gradlew check"), config.hooks["pre-submit-pr"])
        assertEquals(listOf("./gradlew ktlintFormat"), config.hooks["pre-commit"])
        assertNotNull(config.workflows["release"])
        assertEquals("Release workflow", config.workflows["release"]?.description)
        assertEquals(listOf("./gradlew test", "./gradlew buildExecutable"), config.workflows["release"]?.steps)
    }

    @Test
    @DisplayName("Should find config file in parent directory")
    fun testFindConfigInParentDirectory() {
        val rootDir = tempDir.toFile()
        val subDir = File(rootDir, "sub/nested/dir").apply { mkdirs() }
        val configFile = File(rootDir, "aikanban.config.json")
        configFile.writeText(
            """
            {
                "provider": "github",
                "repo": "parent/repo"
            }
            """.trimIndent(),
        )

        val config = AiKanbanConfigLoader.load(subDir, mergeGlobal = false)
        assertEquals("github", config.provider)
        assertEquals("parent/repo", config.repo)
        assertEquals("main", config.defaultBaseBranch) // default retained
    }

    @Test
    @DisplayName("Should save config file and reload correctly")
    fun testSaveAndReloadConfig() {
        val workingDir = tempDir.toFile()
        val configToSave =
            AiKanbanConfig(
                provider = "local-git",
                defaultBaseBranch = "main",
                repo = "myorg/myrepo",
                branchPrefix = "task/",
                verify = listOf("npm test"),
                hooks = mapOf("pre-commit" to listOf("npm run lint")),
                workflows =
                    mapOf(
                        "ci" to
                            CustomWorkflowDefinition(
                                description = "Run full CI pipeline",
                                steps = listOf("npm test", "npm run build"),
                            ),
                    ),
            )

        val savedFile = AiKanbanConfigLoader.save(configToSave, workingDir)
        assertNotNull(savedFile)

        val reloaded = AiKanbanConfigLoader.load(workingDir, mergeGlobal = false)
        assertEquals("local-git", reloaded.provider)
        assertEquals("main", reloaded.defaultBaseBranch)
        assertEquals("myorg/myrepo", reloaded.repo)
        assertEquals("task/", reloaded.branchPrefix)
        assertEquals(listOf("npm test"), reloaded.verify)
        assertEquals(listOf("npm run lint"), reloaded.hooks["pre-commit"])
        assertEquals(listOf("npm test", "npm run build"), reloaded.workflows["ci"]?.steps)
    }

    @Test
    @DisplayName("Should resolve OS-native global config paths correctly for Linux, macOS, and Windows")
    fun testResolveGlobalConfigPaths() {
        val homeDir = "/home/testuser"

        // 1. Linux with XDG_CONFIG_HOME
        val linuxXdg =
            AiKanbanConfigLoader.resolveGlobalConfigPath(
                env = mapOf("XDG_CONFIG_HOME" to "/custom/xdg"),
                osName = "Linux",
                userHome = homeDir,
            )
        assertEquals(File("/custom/xdg/aikanban/config.json"), linuxXdg)

        // 2. Linux fallback (~/.config/aikanban/config.json)
        val linuxDefault =
            AiKanbanConfigLoader.resolveGlobalConfigPath(
                env = emptyMap(),
                osName = "Linux",
                userHome = homeDir,
            )
        assertEquals(File("/home/testuser/.config/aikanban/config.json"), linuxDefault)

        // 3. macOS (~/Library/Application Support/aikanban/config.json)
        val macOS =
            AiKanbanConfigLoader.resolveGlobalConfigPath(
                env = emptyMap(),
                osName = "Mac OS X",
                userHome = homeDir,
            )
        assertEquals(File("/home/testuser/Library/Application Support/aikanban/config.json"), macOS)

        // 4. Windows (%APPDATA%\\aikanban\\config.json)
        val windows =
            AiKanbanConfigLoader.resolveGlobalConfigPath(
                env = mapOf("APPDATA" to "C:\\Users\\testuser\\AppData\\Roaming"),
                osName = "Windows 11",
                userHome = homeDir,
            )
        assertEquals(File("C:\\Users\\testuser\\AppData\\Roaming\\aikanban\\config.json"), windows)

        // 5. Environment variable override AIKANBAN_GLOBAL_CONFIG
        val explicitEnv =
            AiKanbanConfigLoader.resolveGlobalConfigPath(
                env = mapOf("AIKANBAN_GLOBAL_CONFIG" to "/custom/global/config.json"),
                osName = "Linux",
                userHome = homeDir,
            )
        assertEquals(File("/custom/global/config.json"), explicitEnv)
    }

    @Test
    @DisplayName("Should cascade and merge global and project configs properly")
    fun testCascadingMerge() {
        val global =
            AiKanbanConfig(
                provider = "local-git",
                defaultBaseBranch = "main",
                repo = "global/repo",
                branchPrefix = "feature/",
                token = "global-token",
                verify = listOf("global-verify-cmd"),
                hooks =
                    mapOf(
                        "pre-submit-pr" to listOf("global-pre-pr"),
                        "pre-commit" to listOf("global-pre-commit"),
                    ),
                workflows =
                    mapOf(
                        "global-wf" to CustomWorkflowDefinition("Global wf", listOf("echo global")),
                        "override-wf" to CustomWorkflowDefinition("Global override", listOf("echo 1")),
                    ),
            )

        val project =
            AiKanbanConfig(
                provider = "github",
                defaultBaseBranch = "develop",
                repo = "project/repo",
                branchPrefix = "task/",
                token = null,
                verify = listOf("./gradlew test"),
                hooks =
                    mapOf(
                        "pre-submit-pr" to listOf("project-pre-pr"),
                        "post-commit" to listOf("project-post-commit"),
                    ),
                workflows =
                    mapOf(
                        "override-wf" to CustomWorkflowDefinition("Project override", listOf("echo 2")),
                        "project-wf" to CustomWorkflowDefinition("Project wf", listOf("echo project")),
                    ),
            )

        val merged = AiKanbanConfigLoader.merge(global, project)

        // Project overrides
        assertEquals("github", merged.provider)
        assertEquals("develop", merged.defaultBaseBranch)
        assertEquals("project/repo", merged.repo)
        assertEquals("task/", merged.branchPrefix)
        assertEquals("global-token", merged.token) // falls back to global token if project token is null
        assertEquals(listOf("./gradlew test"), merged.verify)

        // Hooks merged: pre-submit-pr overridden, pre-commit retained from global, post-commit added from project
        assertEquals(listOf("project-pre-pr"), merged.hooks["pre-submit-pr"])
        assertEquals(listOf("global-pre-commit"), merged.hooks["pre-commit"])
        assertEquals(listOf("project-post-commit"), merged.hooks["post-commit"])

        // Workflows merged
        assertEquals(3, merged.workflows.size)
        assertEquals(listOf("echo global"), merged.workflows["global-wf"]?.steps)
        assertEquals(listOf("echo 2"), merged.workflows["override-wf"]?.steps)
        assertEquals(listOf("echo project"), merged.workflows["project-wf"]?.steps)
    }
}
