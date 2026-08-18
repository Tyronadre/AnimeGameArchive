package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.tyro.genshinapp.configuration.GenshinContentProperties
import org.junit.jupiter.api.AfterEach
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenshinDbApiClientTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `requests a complete verbose folder from the v5 API`() {
        var query = ""
        val testServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            .also { server = it }
        testServer.createContext("/api/v5/artifacts") { exchange ->
            query = exchange.requestURI.rawQuery
            exchange.respond(
                200,
                """[{"name":"Test Set","version":"6.7","circlet":{"name":"Test Crown"}}]""",
            )
        }
        testServer.start()

        val items = clientFor(testServer).fetchFolder("artifacts")

        assertEquals("Test Set", items.single().path("name").asText())
        assertTrue(query.contains("query=names"))
        assertTrue(query.contains("matchCategories=true"))
        assertTrue(query.contains("verboseCategories=true"))
        assertTrue(query.contains("resultLanguage=English"))
    }

    @Test
    fun `rejects a names-only response so it cannot erase good stored data`() {
        val testServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            .also { server = it }
        testServer.createContext("/api/v5/artifacts") { exchange ->
            exchange.respond(200, """["Test Set"]""")
        }
        testServer.start()

        assertFailsWith<GenshinDbApiException> {
            clientFor(testServer).fetchFolder("artifacts")
        }
    }

    private fun clientFor(testServer: HttpServer): GenshinDbApiClient {
        val properties = GenshinContentProperties().also {
            it.characterApiUrl = "http://127.0.0.1:${testServer.address.port}/api/v5"
        }
        return GenshinDbApiClient(jacksonObjectMapper(), properties)
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
