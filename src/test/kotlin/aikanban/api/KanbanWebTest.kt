package aikanban.api

import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KanbanWebTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var repository: SqliteTaskRepository
    private lateinit var service: KanbanService

    private val jsonConfig =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
            encodeDefaults = true
        }

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("web_test.db").toFile()
        repository = SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}")
        service = DefaultKanbanService(repository)
    }

    @AfterEach
    fun tearDown() {
        service.close()
    }

    private fun withKanbanApp(block: suspend ApplicationTestBuilder.(client: HttpClient) -> Unit) =
        testApplication {
            application {
                kanbanModule(service)
            }
            val client =
                createClient {
                    install(ContentNegotiation) {
                        json(jsonConfig)
                    }
                }
            block(client)
        }

    @Nested
    @DisplayName("Web Dashboard Static Content Delivery")
    inner class StaticContentDeliveryTests {
        @Test
        @DisplayName("GET / returns 200 OK and serves index.html with AiKanban dashboard")
        fun testRootReturnsDashboardHtml() =
            withKanbanApp { client ->
                val response = client.get("/")
                assertEquals(HttpStatusCode.OK, response.status)
                val contentType = response.contentType()
                assertNotNull(contentType)
                assertTrue(contentType.match(ContentType.Text.Html))

                val body = response.bodyAsText()
                assertTrue(body.contains("<title>AiKanban"), "Page should have AiKanban title")
                assertTrue(body.contains("id=\"board\"") || body.contains("class=\"board\""), "Page should have board container")
                assertTrue(body.contains("app.css"), "Page should reference app.css")
                assertTrue(body.contains("app.js"), "Page should reference app.js")
            }

        @Test
        @DisplayName("GET /index.html returns 200 OK and serves html content")
        fun testIndexHtmlRoute() =
            withKanbanApp { client ->
                val response = client.get("/index.html")
                assertEquals(HttpStatusCode.OK, response.status)
                val contentType = response.contentType()
                assertNotNull(contentType)
                assertTrue(contentType.match(ContentType.Text.Html))

                val body = response.bodyAsText()
                assertTrue(body.contains("AiKanban"))
            }

        @Test
        @DisplayName("GET /css/app.css returns 200 OK and serves CSS styling")
        fun testCssResourceRoute() =
            withKanbanApp { client ->
                val response = client.get("/css/app.css")
                assertEquals(HttpStatusCode.OK, response.status)
                val contentType = response.contentType()
                assertNotNull(contentType)
                assertTrue(
                    contentType.match(ContentType.Text.CSS) || contentType.contentType == "text",
                    "Content type should be CSS but was $contentType",
                )

                val body = response.bodyAsText()
                assertTrue(body.isNotEmpty(), "CSS content should not be empty")
                assertTrue(body.contains("--") || body.contains("board") || body.contains("kanban"), "CSS should contain styling rules")
            }

        @Test
        @DisplayName("GET /js/app.js returns 200 OK and serves JavaScript code")
        fun testJsResourceRoute() =
            withKanbanApp { client ->
                val response = client.get("/js/app.js")
                assertEquals(HttpStatusCode.OK, response.status)
                val contentType = response.contentType()
                assertNotNull(contentType)
                assertTrue(
                    contentType.match(ContentType.Application.JavaScript) ||
                        contentType.match(ContentType.Text.JavaScript) ||
                        contentType.contentSubtype.contains("javascript"),
                    "Content type should be JavaScript but was $contentType",
                )

                val body = response.bodyAsText()
                assertTrue(body.isNotEmpty(), "JS content should not be empty")
                assertTrue(body.contains("/api/tasks") || body.contains("/api/columns"), "JS should contain API calls")
            }

        @Test
        @DisplayName("GET /nonexistent-resource.png returns 404 Not Found")
        fun testNonExistentStaticResourceReturns404() =
            withKanbanApp { client ->
                val response = client.get("/nonexistent-resource.png")
                assertEquals(HttpStatusCode.NotFound, response.status)
            }
    }

    @Nested
    @DisplayName("API & Static Route Coexistence")
    inner class RouteCoexistenceTests {
        @Test
        @DisplayName("API routes /api/columns and /api/tasks work correctly alongside static web routing")
        fun testApiRoutesWorkAlongsideStaticResources() =
            withKanbanApp { client ->
                val colResponse = client.get("/api/columns")
                assertEquals(HttpStatusCode.OK, colResponse.status)
                val colContentType = colResponse.contentType()
                assertNotNull(colContentType)
                assertTrue(colContentType.match(ContentType.Application.Json))

                val taskResponse = client.get("/api/tasks")
                assertEquals(HttpStatusCode.OK, taskResponse.status)
                val taskContentType = taskResponse.contentType()
                assertNotNull(taskContentType)
                assertTrue(taskContentType.match(ContentType.Application.Json))
            }
    }
}
