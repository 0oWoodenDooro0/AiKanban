package aikanban.provider

import aikanban.config.AiKanbanConfig
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderFactoryTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var service: KanbanService

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("provider_factory_test.db").toFile()
        service = DefaultKanbanService(SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}"))
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    @Test
    @DisplayName("Should resolve default local-git provider when no override or config is set")
    fun testResolveDefaultProvider() {
        val factory = ProviderFactory(kanbanService = service, workingDir = tempDir.toFile())
        val provider = factory.resolve(overrideProvider = null, config = AiKanbanConfig())

        assertEquals("local-git", provider.name)
        assertTrue(provider is LocalGitProvider)
    }

    @Test
    @DisplayName("Should resolve provider specified by CLI override")
    fun testResolveCliOverride() {
        val factory = ProviderFactory(kanbanService = service, workingDir = tempDir.toFile())
        val config = AiKanbanConfig(provider = "local-git")
        val provider = factory.resolve(overrideProvider = "github", config = config)

        assertEquals("github", provider.name)
        assertTrue(provider is GitHubProvider)
    }

    @Test
    @DisplayName("Should resolve provider configured in AiKanbanConfig")
    fun testResolveFromConfig() {
        val factory = ProviderFactory(kanbanService = service, workingDir = tempDir.toFile())
        val config = AiKanbanConfig(provider = "github", repo = "owner/myrepo")
        val provider = factory.resolve(overrideProvider = null, config = config)

        assertEquals("github", provider.name)
        assertTrue(provider is GitHubProvider)
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when unsupported provider name is specified")
    fun testUnknownProvider() {
        val factory = ProviderFactory(kanbanService = service, workingDir = tempDir.toFile())
        assertThrows<IllegalArgumentException> {
            factory.resolve(overrideProvider = "unsupported-vcs", config = AiKanbanConfig())
        }
    }
}
