package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.PlayerWeapon
import java.nio.file.Files
import java.time.Instant
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

    @Test
    fun `matches traveler equipment across GOOD and legacy aliases`() {
        val state = PlayerCharacterState("Traveler", 80, 3, 5, 6, 8, 8)
        val artifact = PlayerArtifact(
            setKey = "TestSet",
            slotKey = "flower",
            level = 20,
            rarity = 5,
            mainStatKey = "hp",
            location = "Aether",
            locked = false,
            substats = emptyList(),
            totalRolls = null,
            astralMark = false,
            elixirCrafted = false,
        )
        val weapon = PlayerWeapon("DullBlade", 70, 4, 1, "Lumine", false)
        val snapshot = PlayerSnapshot(
            formatVersion = 3,
            source = "test",
            importedAt = Instant.EPOCH,
            characters = listOf(state),
            inventory = emptyMap(),
            inventoryNames = emptyMap(),
            exportedInventoryKeys = 0,
            artifacts = listOf(artifact),
            weapons = listOf(weapon),
        )

        val equipment = equipmentService.equipmentFor(snapshot, state)

        assertEquals(artifact, equipment.artifacts.single())
        assertEquals(weapon, equipment.weapon)
    }
}
