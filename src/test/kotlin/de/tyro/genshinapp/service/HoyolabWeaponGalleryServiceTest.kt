package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.tyro.genshinapp.configuration.GenshinContentProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HoyolabWeaponGalleryServiceTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `finds the weapon entry and persists its awakened gallery image`() {
        val mapper = jacksonObjectMapper()
        val searchRequests = AtomicInteger()
        val detailRequests = AtomicInteger()
        val testServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            .also { server = it }
        testServer.createContext("/search") { exchange ->
            searchRequests.incrementAndGet()
            assertEquals("en-us", exchange.requestHeaders.getFirst("x-rpc-language"))
            exchange.respondJson(
                mapper.writeValueAsBytes(
                    mapOf(
                        "retcode" to 0,
                        "data" to mapOf(
                            "entries" to listOf(
                                mapOf(
                                    "entry_page_id" to "3865",
                                    "name" to "Skyward Blade",
                                    "menu" to mapOf("sub_menus" to listOf(mapOf("id" to "47"))),
                                ),
                                mapOf(
                                    "entry_page_id" to "1954",
                                    "name" to "Skyward Blade",
                                    "menu" to mapOf("sub_menus" to listOf(mapOf("id" to "4"))),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        testServer.createContext("/entry_page") { exchange ->
            detailRequests.incrementAndGet()
            assertEquals("entry_page_id=1954", exchange.requestURI.rawQuery)
            val gallery = mapper.writeValueAsString(
                mapOf(
                    "list" to listOf(
                        mapOf(
                            "key" to "Original",
                            "img" to ORIGINAL_IMAGE_URL,
                            "imgDesc" to "<p>Before Lv.40 Ascension</p>",
                        ),
                        mapOf(
                            "key" to "Awakened",
                            "img" to AWAKENED_IMAGE_URL,
                            "imgDesc" to "<p>After Lv.40 Ascension</p>",
                        ),
                    ),
                ),
            )
            exchange.respondJson(
                mapper.writeValueAsBytes(
                    mapOf(
                        "retcode" to 0,
                        "data" to mapOf(
                            "page" to mapOf(
                                "modules" to listOf(
                                    mapOf(
                                        "components" to listOf(
                                            mapOf(
                                                "component_id" to "gallery_character",
                                                "data" to gallery,
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        testServer.start()

        val store = InMemoryWeaponCatalogStore(
            WeaponDefinition(key = "skywardblade", name = "Skyward Blade", rarity = 5),
        )
        val catalog = WeaponCatalogService(mapper, store)
        val properties = GenshinContentProperties().also {
            it.hoyolabWikiApiUrl = "http://127.0.0.1:${testServer.address.port}"
        }
        val service = HoyolabWeaponGalleryService(mapper, properties, catalog)

        val first = assertNotNull(service.enrich("SkywardBlade"))
        val second = assertNotNull(service.enrich("skywardblade"))

        assertEquals(1954, first.hoyolabEntryId)
        assertEquals(AWAKENED_IMAGE_URL, first.fullImageUrl)
        assertEquals(2, first.galleryImages.size)
        assertEquals("After Lv.40 Ascension", first.galleryImages.last().description)
        assertEquals(first, second)
        assertEquals(first, store.findWeapon("skywardblade"))
        assertEquals(1, searchRequests.get())
        assertEquals(1, detailRequests.get())
    }

    private class InMemoryWeaponCatalogStore(initial: WeaponDefinition) : WeaponCatalogStore {
        private val weapons = ConcurrentHashMap<String, WeaponDefinition>().also {
            it[initial.key] = initial
        }

        override fun getWeapons(): List<WeaponDefinition> = weapons.values.toList()

        override fun findWeapon(key: String): WeaponDefinition? = weapons[key.lowercase()]

        override fun saveWeapon(weapon: WeaponDefinition): WeaponDefinition = weapon.also {
            weapons[weapon.key] = weapon
        }
    }

    private fun HttpExchange.respondJson(bytes: ByteArray) {
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    companion object {
        private const val ORIGINAL_IMAGE_URL =
            "https://upload-static.hoyoverse.com/hoyolab-wiki/original.png"
        private const val AWAKENED_IMAGE_URL =
            "https://upload-static.hoyoverse.com/hoyolab-wiki/awakened.png"
    }
}
