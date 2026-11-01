package de.tyro.genshinapp.presentation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.service.presentation.CharacterStageCatalog
import de.tyro.genshinapp.service.presentation.CharacterStageManifest
import de.tyro.genshinapp.service.presentation.CharacterStageProperties
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.ClassPathResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CharacterStageCatalogTest {
    @TempDir lateinit var temporary: Path
    private val mapper = jacksonObjectMapper()
    private fun catalog() = CharacterStageCatalog(CharacterStageProperties().also { it.modelDirectory = temporary.toString() }, mapper)

    @Test fun `fitted Lumine is portable without the external model folder`() {
        val catalog = catalog()
        val entry = assertNotNull(catalog.find("TravelerLumine"))
        assertTrue(entry.bundled)
        assertTrue(entry.fittedSkirtClearance)
        assertNotNull(catalog.asset("lumine", entry.model))
        assertNull(catalog.find("travelerAether"))
    }

    @Test fun `incomplete external models fall back until every referenced asset exists`() {
        val entry = ClassPathResource("data/character-stages.json").inputStream.use {
            mapper.readValue<CharacterStageManifest>(it).models.first { model -> model.key == "nefer" }
        }
        assertNull(catalog().find(entry.key))
        entry.assets.forEach { asset ->
            val file = temporary.resolve(entry.directory).resolve(asset)
            Files.createDirectories(file.parent); Files.write(file, byteArrayOf(1))
        }
        val catalog = catalog()
        assertEquals(entry.key, assertNotNull(catalog.find("NEFER")).key)
        assertNotNull(catalog.asset(entry.key, entry.model))
        val privateFile = temporary.resolve(entry.directory).resolve("private.txt")
        Files.writeString(privateFile, "not a model asset")
        assertNull(catalog.asset(entry.key, "private.txt"))
        assertNull(catalog.asset(entry.key, "../private.txt"))
        assertNull(catalog.asset(entry.key, "tex/../${entry.model}"))
        assertNull(catalog.asset(entry.key, "tex\\${entry.model}"))
    }

    @Test fun `idle assets contain complete poses and numbered-source provenance`() {
        val provenance = ClassPathResource("static/character-stage/motions/provenance.json").inputStream.use { mapper.readTree(it) }
        for (profile in listOf("female", "male", "child")) {
            val source = provenance[profile]
            assertTrue(source["motionSource"].asText().matches(Regex("^[1234]_.+")))
            assertTrue(source["baseSource"].asText().matches(Regex("^[1234]_.+")))
            assertTrue(source["baseForwardTravelRemoved"].asBoolean())
            val bytes = ClassPathResource("static/character-stage/motions/$profile-idle.vmd").inputStream.use { it.readBytes() }
            val count = java.nio.ByteBuffer.wrap(bytes, 50, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            assertTrue(count > 100, "The partial idle must include its missing base-pose tracks")
            assertTrue(bytes.size < 100 * 1024)
        }
    }
}
