package de.tyro.genshinapp.startup

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StartupAnimationAssetTest {
    @Test
    fun `loading animation is small transparent and sourced from the numbered collection`() {
        val bytes = ClassPathResource("static/startup/lumine-sprint.png").inputStream.use { it.readBytes() }
        assertTrue(bytes.size < 256 * 1024, "The first-paint animation must stay below 256 KiB")
        val image = ImageIO.read(bytes.inputStream())
        assertEquals(256 * 18, image.width)
        assertEquals(320, image.height)
        assertTrue(image.colorModel.hasAlpha())

        val source = ClassPathResource("static/startup/lumine-sprint-source.json").inputStream.use {
            jacksonObjectMapper().readTree(it)
        }
        assertTrue(source["motionSource"].asText().startsWith("2_Female_Motions/"))
        assertTrue(source["motionSource"].asText().contains("Sprint_A"))
        assertTrue(source["inPlace"].asBoolean())
        assertTrue(source["originalArmSwing"].asBoolean())
        assertEquals(18, source["frames"].asInt())
        assertEquals(30, source["fps"].asInt())
    }

    @Test
    fun `reduced motion has a lightweight static frame`() {
        val bytes = ClassPathResource("static/startup/lumine-sprint-poster.png").inputStream.use { it.readBytes() }
        assertTrue(bytes.size < 32 * 1024)
        val image = ImageIO.read(bytes.inputStream())
        assertEquals(256, image.width)
        assertEquals(320, image.height)
        assertTrue(image.colorModel.hasAlpha())
    }

    @Test
    fun `companion start and stop use authored numbered motions without master travel`() {
        mapOf("start" to (22 to "290L-220_"), "stop" to (26 to "220-end_")).forEach { (clip, expected) ->
            val bytes = ClassPathResource("static/companion/lumine-$clip.png").inputStream.use { it.readBytes() }
            assertTrue(bytes.size < 384 * 1024, "Deferred transition assets must stay below 384 KiB each")
            val image = ImageIO.read(bytes.inputStream())
            assertEquals(256 * expected.first, image.width)
            assertEquals(320, image.height)
            assertTrue(image.colorModel.hasAlpha())
            val source = ClassPathResource("static/companion/lumine-$clip-source.json").inputStream.use {
                jacksonObjectMapper().readTree(it)
            }
            assertTrue(source["motionSource"].asText().startsWith("2_Female_Motions/4_Master_Root_Movement_Motions/"))
            assertTrue(source["motionSource"].asText().contains(expected.second))
            assertTrue(source["basePoseSource"].asText().startsWith("2_Female_Motions/"))
            assertTrue(source["inPlace"].asBoolean())
            assertTrue(source["masterTravelRemoved"].asBoolean())
            assertEquals(false, source["loop"].asBoolean())
            assertEquals(10, source["fps"].asInt())
        }
    }
}
