package aikanban.config

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AiKanbanConfigTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("Should return default config when no config file exists")
    fun testDefaultConfigWhenMissing() {
        val workingDir = tempDir.toFile()
        val config = AiKanbanConfigLoader.load(workingDir)

        assertEquals("local-git", config.provider)
        assertEquals("main", config.defaultBaseBranch)
        assertEquals("feature/", config.branchPrefix)
        assertNull(config.repo)
        assertNull(config.token)
    }

    @Test
    @DisplayName("Should load valid .aikanban.json configuration file")
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
                "token": "secret-token"
            }
            """.trimIndent(),
        )

        val config = AiKanbanConfigLoader.load(workingDir)
        assertEquals("github", config.provider)
        assertEquals("master", config.defaultBaseBranch)
        assertEquals("owner/custom-repo", config.repo)
        assertEquals("feat/", config.branchPrefix)
        assertEquals("secret-token", config.token)
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

        val config = AiKanbanConfigLoader.load(subDir)
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
            )

        val savedFile = AiKanbanConfigLoader.save(configToSave, workingDir)
        assertNotNull(savedFile)

        val reloaded = AiKanbanConfigLoader.load(workingDir)
        assertEquals("local-git", reloaded.provider)
        assertEquals("main", reloaded.defaultBaseBranch)
        assertEquals("myorg/myrepo", reloaded.repo)
        assertEquals("task/", reloaded.branchPrefix)
    }
}
