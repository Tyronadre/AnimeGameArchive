package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterTalent
import de.tyro.genshinapp.model.CharacterTalentKind
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.WeaponImageType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicContentLoaderTest {
    @TempDir
    lateinit var cacheDirectory: Path

    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `downloads missing character data and reuses the file cache`() {
        val characterRequests = AtomicInteger()
        val talentRequests = AtomicInteger()
        val testServer = startServer()
        testServer.createContext("/characters") { exchange ->
            characterRequests.incrementAndGet()
            exchange.respond(
                "application/json",
                """{"id":999,"name":"Remote Hero","costs":{},"images":{}}""".toByteArray(),
            )
        }
        testServer.createContext("/talents") { exchange ->
            talentRequests.incrementAndGet()
            exchange.respond(
                "application/json",
                """{"costs":{"lvl2":[{"id":202,"name":"Mora","count":1000}]}}""".toByteArray(),
            )
        }
        testServer.start()

        val loader = loaderFor(testServer)
        val firstLoad = assertNotNull(loader.loadCharacterJson("remotehero"))
        val secondLoad = assertNotNull(loader.loadCharacterJson("remotehero"))

        assertEquals("Remote Hero", firstLoad.path("name").asText())
        assertEquals(1000, firstLoad.path("talents").path("costs").path("lvl2")[0].path("count").asInt())
        assertEquals(firstLoad, secondLoad)
        assertEquals(1, characterRequests.get())
        assertEquals(1, talentRequests.get())
        assertTrue(Files.isRegularFile(cacheDirectory.resolve("characters/data/remotehero.json")))
    }

    @Test
    fun `downloads missing material image and reuses the file cache`() {
        val imageRequests = AtomicInteger()
        val requestedPaths = mutableListOf<String>()
        val pngBytes = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val testServer = startServer()
        testServer.createContext("/material/") { exchange ->
            imageRequests.incrementAndGet()
            synchronized(requestedPaths) {
                requestedPaths += exchange.requestURI.rawPath
            }
            exchange.respond("image/png", pngBytes)
        }
        testServer.start()

        val loader = loaderFor(testServer)
        val firstLoad = assertNotNull(loader.loadMaterialImage(123, "Test Item"))
        val secondLoad = assertNotNull(loader.loadMaterialImage(123, "Test Item"))

        assertContentEquals(pngBytes, firstLoad.bytes)
        assertContentEquals(pngBytes, secondLoad.bytes)
        assertEquals("image/png", firstLoad.contentType)
        assertEquals(1, imageRequests.get())
        assertTrue(requestedPaths.single().endsWith("/a/ac/Item_Test_Item.png"))
        assertTrue(Files.isRegularFile(cacheDirectory.resolve("materials/123.image")))
    }

    @Test
    fun `downloads an artifact icon from its Wikia item path and reuses the cache`() {
        val imageRequests = AtomicInteger()
        val requestedPaths = mutableListOf<String>()
        val pngBytes = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val testServer = startServer()
        testServer.createContext("/material/") { exchange ->
            imageRequests.incrementAndGet()
            synchronized(requestedPaths) {
                requestedPaths += exchange.requestURI.rawPath
            }
            exchange.respond("image/png", pngBytes)
        }
        testServer.start()

        val loader = loaderFor(testServer)
        val firstLoad = assertNotNull(
            loader.loadArtifactImage("heartofdepth", "flower", "Gilded Corsage"),
        )
        val secondLoad = assertNotNull(
            loader.loadArtifactImage("heartofdepth", "flower", "Gilded Corsage"),
        )

        assertContentEquals(pngBytes, firstLoad.bytes)
        assertContentEquals(pngBytes, secondLoad.bytes)
        assertEquals(1, imageRequests.get())
        assertTrue(requestedPaths.single().endsWith("/4/40/Item_Gilded_Corsage.png"))
        assertTrue(
            Files.isRegularFile(cacheDirectory.resolve("artifacts/heartofdepth-flower.image")),
        )
    }

    @Test
    fun `downloads a weapon icon from its Wikia weapon path and reuses the cache`() {
        val imageRequests = AtomicInteger()
        val requestedPaths = mutableListOf<String>()
        val pngBytes = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val testServer = startServer()
        testServer.createContext("/material/") { exchange ->
            imageRequests.incrementAndGet()
            synchronized(requestedPaths) {
                requestedPaths += exchange.requestURI.rawPath
            }
            exchange.respond("image/png", pngBytes)
        }
        testServer.start()

        val loader = loaderFor(testServer)
        val firstLoad = assertNotNull(
            loader.loadWeaponImage(
                "wolfsgravestone",
                "Wolf's Gravestone",
                WeaponImageType.ICON,
                null,
            ),
        )
        val secondLoad = assertNotNull(
            loader.loadWeaponImage(
                "wolfsgravestone",
                "Wolf's Gravestone",
                WeaponImageType.ICON,
                null,
            ),
        )

        assertContentEquals(pngBytes, firstLoad.bytes)
        assertContentEquals(pngBytes, secondLoad.bytes)
        assertEquals(1, imageRequests.get())
        assertTrue(requestedPaths.single().endsWith("/4/4f/Weapon_Wolf%27s_Gravestone.png"))
        assertTrue(Files.isRegularFile(cacheDirectory.resolve("weapons/wolfsgravestone-icon.image")))
    }

    @Test
    fun `registers and serves an admin override for a weapon image`() {
        val pngBytes = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val testServer = startServer()
        testServer.createContext("/custom-weapon.png") { exchange ->
            exchange.respond("image/png", pngBytes)
        }
        testServer.start()

        val fixture = fixtureFor(testServer)
        val correctedUrl =
            "http://127.0.0.1:${testServer.address.port}/custom-weapon.png"
        fixture.registry.registerWeaponDefaults(
            listOf(
                WeaponImageDefault(
                    key = "wolfsgravestone",
                    imageType = WeaponImageType.ICON,
                    name = "Wolf's Gravestone",
                    defaultUrl = "http://127.0.0.1:${testServer.address.port}/default.png",
                ),
            ),
        )

        val result = fixture.loader.updateWeaponImageUrl(
            "wolfsgravestone",
            "Wolf's Gravestone",
            WeaponImageType.ICON,
            correctedUrl,
        )
        val cachedImage = assertNotNull(
            fixture.loader.loadWeaponImage(
                "wolfsgravestone",
                "Wolf's Gravestone",
                WeaponImageType.ICON,
                null,
            ),
        )

        assertTrue(result.successful)
        assertEquals(
            correctedUrl,
            fixture.registry.weaponLink("wolfsgravestone", WeaponImageType.ICON)?.url,
        )
        assertContentEquals(pngBytes, cachedImage.bytes)
        assertTrue(
            Files.readString(cacheDirectory.resolve("image-links.json")).contains("weaponImages"),
        )
    }

    @Test
    fun `downloads and caches a discovered full weapon image`() {
        val imageRequests = AtomicInteger()
        val pngBytes = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val testServer = startServer()
        testServer.createContext("/skyward-awakened.png") { exchange ->
            imageRequests.incrementAndGet()
            exchange.respond("image/png", pngBytes)
        }
        testServer.start()

        val fixture = fixtureFor(testServer)
        val imageUrl = "http://127.0.0.1:${testServer.address.port}/skyward-awakened.png"
        fixture.registry.registerWeaponDefaults(
            listOf(
                WeaponImageDefault(
                    "skywardblade",
                    WeaponImageType.FULL_ASCENDED,
                    "Skyward Blade full image · Ascended",
                    imageUrl,
                ),
            ),
        )

        val first = assertNotNull(
            fixture.loader.loadWeaponImage(
                "skywardblade",
                "Skyward Blade",
                WeaponImageType.FULL_ASCENDED,
                imageUrl,
            ),
        )
        val second = assertNotNull(
            fixture.loader.loadWeaponImage(
                "skywardblade",
                "Skyward Blade",
                WeaponImageType.FULL_ASCENDED,
                imageUrl,
            ),
        )

        assertContentEquals(pngBytes, first.bytes)
        assertContentEquals(pngBytes, second.bytes)
        assertEquals(1, imageRequests.get())
        assertEquals(
            DynamicContentLoader.ImageState.CACHED,
            fixture.loader.weaponImageState(
                "skywardblade",
                "Skyward Blade",
                WeaponImageType.FULL_ASCENDED,
                imageUrl,
            ),
        )
        assertTrue(
            Files.isRegularFile(
                cacheDirectory.resolve("weapons/skywardblade-full-ascended.image"),
            ),
        )
    }

    @Test
    fun `downloads a talent icon from its sanitized Wikia talent path and reuses the cache`() {
        val imageRequests = AtomicInteger()
        val requestedPaths = mutableListOf<String>()
        val pngBytes = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val testServer = startServer()
        testServer.createContext("/material/") { exchange ->
            imageRequests.incrementAndGet()
            synchronized(requestedPaths) {
                requestedPaths += exchange.requestURI.rawPath
            }
            exchange.respond("image/png", pngBytes)
        }
        testServer.start()

        val loader = loaderFor(testServer)
        val firstLoad = assertNotNull(
            loader.loadTalentImage("kamisatoayaka", "combat2", "Kamisato Art: Hyouka"),
        )
        val secondLoad = assertNotNull(
            loader.loadTalentImage("kamisatoayaka", "combat2", "Kamisato Art: Hyouka"),
        )

        assertContentEquals(pngBytes, firstLoad.bytes)
        assertContentEquals(pngBytes, secondLoad.bytes)
        assertEquals(1, imageRequests.get())
        assertTrue(requestedPaths.single().endsWith("/5/56/Talent_Kamisato_Art_Hyouka.png"))
        assertTrue(
            Files.isRegularFile(
                cacheDirectory.resolve("characters/talents/kamisatoayaka-combat2.image"),
            ),
        )
    }

    @Test
    fun `uses the shared weapon element icon for a normal attack`() {
        val requestedPaths = mutableListOf<String>()
        val pngBytes = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val testServer = startServer()
        testServer.createContext("/material/") { exchange ->
            synchronized(requestedPaths) {
                requestedPaths += exchange.requestURI.rawPath
            }
            exchange.respond("image/png", pngBytes)
        }
        testServer.start()

        val image = assertNotNull(
            loaderFor(testServer).loadTalentImage(
                "kamisatoayaka",
                "combat1",
                "Kamisato Art: Kabuki",
                normalAttackWeapon = "Sword",
                normalAttackElement = "Cryo",
            ),
        )

        assertContentEquals(pngBytes, image.bytes)
        assertTrue(requestedPaths.single().endsWith("/6/6a/Sword_Cryo.png"))
    }

    @Test
    fun `persists an admin url only after loading the image successfully`() {
        val imageRequests = AtomicInteger()
        val pngBytes = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val testServer = startServer()
        testServer.createContext("/custom-image.png") { exchange ->
            imageRequests.incrementAndGet()
            exchange.respond("image/png", pngBytes)
        }
        testServer.start()

        val fixture = fixtureFor(testServer)
        val material = MaterialDefinition(321, "Corrected Material")
        val correctedUrl = "http://127.0.0.1:${testServer.address.port}/custom-image.png"

        val result = fixture.loader.updateMaterialImageUrl(material, correctedUrl)
        val cachedImage = assertNotNull(
            fixture.loader.loadMaterialImage(material.id, material.name),
        )

        assertTrue(result.successful)
        assertEquals(correctedUrl, fixture.registry.materialLink(material.id)?.url)
        assertContentEquals(pngBytes, cachedImage.bytes)
        assertEquals(1, imageRequests.get())
        assertTrue(Files.readString(cacheDirectory.resolve("image-links.json")).contains(correctedUrl))
    }

    @Test
    fun `registers and serves an admin override for a talent image`() {
        val pngBytes = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
        val testServer = startServer()
        testServer.createContext("/custom-talent.png") { exchange ->
            exchange.respond("image/png", pngBytes)
        }
        testServer.start()

        val fixture = fixtureFor(testServer)
        val talent = CharacterTalent(
            key = "combat2",
            kind = CharacterTalentKind.ELEMENTAL_SKILL,
            name = "Kamisato Art: Hyouka",
            description = "Deals Cryo DMG.",
            flavorText = null,
        )
        val character = characterWith(talent)
        fixture.loader.registerDefaultImageLinks(listOf(character), emptyList())
        val correctedUrl =
            "http://127.0.0.1:${testServer.address.port}/custom-talent.png"

        assertTrue(
            fixture.registry.talentLink(character.key, talent.key)
                ?.defaultUrl
                .orEmpty()
                .endsWith("/5/56/Talent_Kamisato_Art_Hyouka.png"),
        )

        val result = fixture.loader.updateTalentImageUrl(character, talent, correctedUrl)
        val cachedImage = assertNotNull(
            fixture.loader.loadTalentImage(character.key, talent.key, talent.name),
        )

        assertTrue(result.successful)
        assertEquals(correctedUrl, fixture.registry.talentLink(character.key, talent.key)?.url)
        assertContentEquals(pngBytes, cachedImage.bytes)
        assertTrue(
            Files.isRegularFile(
                cacheDirectory.resolve("characters/talents/kamisatoayaka-combat2.image"),
            ),
        )
        assertTrue(Files.readString(cacheDirectory.resolve("image-links.json")).contains("talents"))
    }

    private fun startServer(): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server = it }

    private fun loaderFor(testServer: HttpServer): DynamicContentLoader {
        return fixtureFor(testServer).loader
    }

    private fun fixtureFor(testServer: HttpServer): LoaderFixture {
        val baseUrl = "http://127.0.0.1:${testServer.address.port}"
        val objectMapper = jacksonObjectMapper()
        val properties = GenshinContentProperties().also {
            it.cacheDirectory = cacheDirectory.toString()
            it.characterApiUrl = baseUrl
            it.fandomImageBaseUrl = "$baseUrl/material"
        }
        val registry = ImageUrlRegistry(objectMapper, properties)
        return LoaderFixture(
            loader = DynamicContentLoader(
                objectMapper,
                properties,
                registry,
                FandomImageUrlResolver(properties),
            ),
            registry = registry,
        )
    }

    private fun characterWith(talent: CharacterTalent): CharacterDefinition = CharacterDefinition(
        key = "kamisatoayaka",
        id = 10000002,
        name = "Kamisato Ayaka",
        title = null,
        description = null,
        weapon = "Sword",
        rarity = 5,
        birthday = null,
        element = "Cryo",
        affiliation = null,
        region = "Inazuma",
        constellation = null,
        ascensionStatType = null,
        imageUrls = emptyMap(),
        remoteImageUrls = emptyMap(),
        ascensionCosts = emptyMap(),
        talentCosts = emptyMap(),
        talents = listOf(talent),
    )

    private fun HttpExchange.respond(contentType: String, bytes: ByteArray) {
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    private data class LoaderFixture(
        val loader: DynamicContentLoader,
        val registry: ImageUrlRegistry,
    )
}
