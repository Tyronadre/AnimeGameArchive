package de.tyro.genshinapp.service.importer

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import de.tyro.genshinapp.configuration.GenshinContentProperties
import java.net.InetSocketAddress
import kotlin.test.*

class HoyolabCatalogProbeTest {
    @Test
    fun `probe requests one item and reads the category total`() {
        val mapper = jacksonObjectMapper()
        var receivedSize = -1
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/get_entry_page_list") { exchange ->
            val body = mapper.readTree(exchange.requestBody)
            receivedSize = body.path("page_size").asInt()
            val list = if (body.path("page_num").asInt() == 1)
                """[{"entry_page_id":"164","name":"Teachings of Freedom"}]""" else "[]"
            val response = """{"retcode":0,"data":{"total":"4155","list":$list}}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val properties = GenshinContentProperties().apply {
                hoyolabWikiApiUrl = "http://127.0.0.1:${server.address.port}"
            }
            val source = HttpHoyolabWikiDataSource(mapper, properties)
            assertEquals(HoyolabCatalogProbe(4155, 164), source.probeCatalog(HoyolabWikiMenu.MATERIALS))
            assertEquals(1, receivedSize)
            // A prematurely empty next page must not be accepted as a complete catalog.
            assertFailsWith<IllegalStateException> { source.listEntries(HoyolabWikiMenu.MATERIALS) }
        } finally { server.stop(0) }
    }
}
