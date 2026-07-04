package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlayerEquipmentServiceTest {
    private val importService = GoodImportService(jacksonObjectMapper())
    private val equipmentService = PlayerEquipmentService()

    @Test
    fun `finds equipped weapon and artifacts and aggregates their substats`() {
        val snapshot = importService.parse(
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        val furina = snapshot.characters.first { it.key == "Furina" }

        val equipment = equipmentService.equipmentFor(snapshot, furina)

        assertNotNull(equipment.weapon)
        assertEquals(5, equipment.artifactCount)
        assertEquals(
            listOf("flower", "plume", "sands", "goblet", "circlet"),
            equipment.artifacts.map { it.slotKey.lowercase() },
        )
        assertTrue(equipment.artifactStats.isNotEmpty())

        val expectedCritRate = equipment.artifacts
            .flatMap { it.substats }
            .filter { it.key == "critRate_" }
            .sumOf { it.value }
        val displayedCritRate = equipment.artifactStats
            .first { it.key == "critRate_" }

        assertEquals(expectedCritRate, displayedCritRate.value, 0.001)
        assertTrue(displayedCritRate.formattedValue.endsWith(" %"))
    }
}
