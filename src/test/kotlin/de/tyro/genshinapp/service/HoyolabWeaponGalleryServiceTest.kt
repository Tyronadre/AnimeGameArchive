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
    fun `imports and persists the structured weapon wiki page`() {
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
            val baseInfo = mapper.writeValueAsString(
                mapOf(
                    "list" to listOf(
                        mapOf("key" to "Name", "value" to listOf("Skyward Blade")),
                        mapOf("key" to "Region", "value" to listOf("<p>Mondstadt</p>")),
                        mapOf("key" to "Source", "value" to listOf("<p>Wishes</p>")),
                        mapOf("key" to "Type", "value" to listOf("Sword")),
                        mapOf("key" to "Secondary Attributes", "value" to listOf("CRIT Rate")),
                        mapOf(
                            "key" to "Sky-Piercing Fang",
                            "value" to listOf("<p>Increases CRIT Rate.<br>Triggers a vacuum blade.</p>"),
                        ),
                        mapOf("key" to "Version Released", "value" to listOf("<p>1.0</p>")),
                    ),
                ),
            )
            val material = "\$" + mapper.writeValueAsString(
                listOf(
                    mapOf(
                        "ep_id" to 757,
                        "img" to MATERIAL_IMAGE_URL,
                        "amount" to 10_000,
                        "nickname" to "Mora",
                    ),
                ),
            ) + "\$"
            val ascension = mapper.writeValueAsString(
                mapOf(
                    "list" to listOf(
                        mapOf(
                            "key" to "Lv.1",
                            "combatList" to listOf(
                                mapOf(
                                    "values" to listOf(
                                        "ATK before Ascension",
                                        "ATK after Ascension",
                                        "CRIT Rate",
                                    ),
                                ),
                                mapOf("values" to listOf("-", "49", "2.4％")),
                            ),
                            "materials" to emptyList<String>(),
                        ),
                        mapOf(
                            "key" to "Lv.20",
                            "combatList" to listOf(
                                mapOf(
                                    "values" to listOf(
                                        "ATK before Ascension",
                                        "ATK after Ascension",
                                        "CRIT Rate",
                                    ),
                                ),
                                mapOf("values" to listOf("145", "176", "4.2%")),
                            ),
                            "materials" to listOf(material),
                        ),
                    ),
                ),
            )
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
            val story = mapper.writeValueAsString(
                mapOf("list" to listOf(mapOf("desc" to "<p>First line.<br>Second line.</p>"))),
            )
            exchange.respondJson(
                mapper.writeValueAsBytes(
                    mapOf(
                        "retcode" to 0,
                        "data" to mapOf(
                            "page" to mapOf(
                                "id" to "1954",
                                "name" to "Skyward Blade",
                                "desc" to "The fang that pierces the sky.",
                                "icon_url" to WIKI_ICON_URL,
                                "version" to "12345",
                                "filter_values" to mapOf(
                                    "weapon_rarity" to mapOf(
                                        "values" to listOf("5-Star"),
                                        "value_types" to listOf(mapOf("enum_string" to "5")),
                                    ),
                                ),
                                "modules" to listOf(
                                    mapOf(
                                        "components" to listOf(
                                            mapOf("component_id" to "baseInfo", "data" to baseInfo),
                                            mapOf("component_id" to "ascension", "data" to ascension),
                                            mapOf(
                                                "component_id" to "gallery_character",
                                                "data" to gallery,
                                            ),
                                            mapOf("component_id" to "story", "data" to story),
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
        assertEquals(1, first.hoyolabDataVersion)
        assertEquals("12345", first.hoyolabPageVersion)
        assertEquals(WIKI_ICON_URL, first.hoyolabIconUrl)
        assertEquals("The fang that pierces the sky.", first.description)
        assertEquals("Mondstadt", first.region)
        assertEquals("Wishes", first.obtainMethod)
        assertEquals("1.0", first.releaseVersion)
        assertEquals("Sword", first.weaponType)
        assertEquals("FIGHT_PROP_CRITICAL", first.secondaryStatType)
        assertEquals(49.0, first.baseAttack)
        assertEquals(2.4, first.baseSecondaryStat)
        assertEquals("Sky-Piercing Fang", first.passiveName)
        assertEquals("Increases CRIT Rate.\nTriggers a vacuum blade.", first.passiveDescription)
        assertEquals("First line.\nSecond line.", first.story)
        assertEquals(AWAKENED_IMAGE_URL, first.fullImageUrl)
        assertEquals(2, first.galleryImages.size)
        assertEquals("After Lv.40 Ascension", first.galleryImages.last().description)
        assertEquals(2, first.hoyolabAscension.size)
        assertEquals(176.0, first.hoyolabAscension.last().attackAfterAscension)
        assertEquals("Mora", first.hoyolabAscension.last().materials.single().name)
        assertEquals(10_000, first.hoyolabAscension.last().materials.single().amount)
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
        private const val WIKI_ICON_URL =
            "https://act-webstatic.hoyoverse.com/hoyolab-wiki/icon.png"
        private const val MATERIAL_IMAGE_URL =
            "https://bbs.hoyolab.com/hoyowiki/picture/object/Mora_icon.png"
    }
}
