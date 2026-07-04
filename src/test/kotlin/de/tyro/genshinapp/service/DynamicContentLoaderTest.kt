package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.MaterialDefinition
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
            loader.loadWeaponImage("wolfsgravestone", "Wolf's Gravestone"),
        )
        val secondLoad = assertNotNull(
            loader.loadWeaponImage("wolfsgravestone", "Wolf's Gravestone"),
        )

        assertContentEquals(pngBytes, firstLoad.bytes)
        assertContentEquals(pngBytes, secondLoad.bytes)
        assertEquals(1, imageRequests.get())
        assertTrue(requestedPaths.single().endsWith("/4/4f/Weapon_Wolf%27s_Gravestone.png"))
        assertTrue(Files.isRegularFile(cacheDirectory.resolve("weapons/wolfsgravestone.image")))
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
