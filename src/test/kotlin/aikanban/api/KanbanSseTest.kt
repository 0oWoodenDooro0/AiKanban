package aikanban.api

import aikanban.model.BoardColumn
import aikanban.model.TaskPriority
import aikanban.repository.SqliteTaskRepository
import aikanban.service.DefaultKanbanService
import aikanban.service.KanbanService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KanbanSseTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: File
    private lateinit var repository: SqliteTaskRepository
    private lateinit var service: KanbanService
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private val client = HttpClient()

    @BeforeEach
    fun setUp() {
        dbFile = tempDir.resolve("sse_test.db").toFile()
        repository = SqliteTaskRepository("jdbc:sqlite:${dbFile.absolutePath}")
        service = DefaultKanbanService(repository)
    }

    @AfterEach
    fun tearDown() {
        client.close()
        server?.stop(500, 1000)
        service.close()
    }

    @Test
    @DisplayName("GET /api/events should stream TaskCreated and TaskMoved events in real-time")
    fun testRealtimeTaskEvents() {
        val s = createKanbanServer(port = 0, host = "127.0.0.1", service = service)
        s.start(wait = false)
        server = s

        val eventList = CopyOnWriteArrayList<String>()

        runBlocking(Dispatchers.IO) {
            val port = s.engine.resolvedConnectors().first().port
            val job =
                async {
                    client.prepareGet("http://127.0.0.1:$port/api/events") {
                        headers.append(HttpHeaders.Accept, "text/event-stream")
                    }.execute { response ->
                        assertEquals(HttpStatusCode.OK, response.status)
                        val channel: ByteReadChannel = response.body()
                        while (!channel.isClosedForRead) {
                            val line = channel.readUTF8Line() ?: break
                            if (line.isNotBlank()) {
                                eventList.add(line)
                                if (eventList.size >= 4) {
                                    break
                                }
                            }
                        }
                    }
                }

            // Give the SSE subscription a short moment to connect
            delay(300)

            // Trigger events via service
            val created =
                service.createTask(
                    title = "SSE Test Task",
                    description = "Testing SSE",
                    priority = TaskPriority.HIGH,
                    operator = "test-runner",
                )
            service.moveTask(
                taskId = created.id,
                toStatus = "IN_PROGRESS",
                operator = "agent-x",
                comment = "Started work",
            )

            withTimeout(5000) {
                job.await()
            }
        }

        // Verify SSE lines contain event and data
        assertTrue(eventList.any { it.startsWith("event: TaskCreated") || it.contains("TaskCreated") })
        assertTrue(eventList.any { it.contains("SSE Test Task") })
        assertTrue(eventList.any { it.startsWith("event: TaskMoved") || it.contains("TaskMoved") })
        assertTrue(eventList.any { it.contains("IN_PROGRESS") })
    }

    @Test
    @DisplayName("GET /api/events should stream ColumnCreated and ColumnUpdated events")
    fun testRealtimeColumnEvents() {
        val s = createKanbanServer(port = 0, host = "127.0.0.1", service = service)
        s.start(wait = false)
        server = s

        val eventList = CopyOnWriteArrayList<String>()

        runBlocking(Dispatchers.IO) {
            val port = s.engine.resolvedConnectors().first().port
            val job =
                async {
                    client.prepareGet("http://127.0.0.1:$port/api/events") {
                        headers.append(HttpHeaders.Accept, "text/event-stream")
                    }.execute { response ->
                        val channel: ByteReadChannel = response.body()
                        while (!channel.isClosedForRead) {
                            val line = channel.readUTF8Line() ?: break
                            if (line.isNotBlank()) {
                                eventList.add(line)
                                if (eventList.size >= 4) {
                                    break
                                }
                            }
                        }
                    }
                }

            delay(300)

            val customCol = BoardColumn(id = "TESTING", name = "Testing Phase", order = 5)
            service.createColumn(customCol)
            service.updateColumn(customCol.copy(name = "QA Testing"))

            withTimeout(5000) {
                job.await()
            }
        }

        assertTrue(eventList.any { it.contains("ColumnCreated") || it.contains("TESTING") })
        assertTrue(eventList.any { it.contains("ColumnUpdated") || it.contains("QA Testing") })
    }
}
