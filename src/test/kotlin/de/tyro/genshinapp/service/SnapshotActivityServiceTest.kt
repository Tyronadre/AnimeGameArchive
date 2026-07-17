package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerArtifactStat
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.PlayerWeapon
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotActivityServiceTest {
    @TempDir
    lateinit var cacheDirectory: Path

    @Test
    fun `detects collected materials and progression upgrades`() {
        val previous = snapshot(
            inventory = mapOf("mora" to 1_000L, "mysticore" to 10L),
            artifacts = listOf(artifact(level = 4, location = "Furina")),
            weapons = listOf(weapon(level = 20)),
            characters = listOf(character(level = 40)),
        )
        val current = snapshot(
            inventory = mapOf("mora" to 1_250L, "mysticore" to 6L),
            artifacts = listOf(
                artifact(
                    level = 8,
                    location = "Furina",
                    substats = listOf(
                        PlayerArtifactStat("critRate_", 6.2),
                        PlayerArtifactStat("atk_", 5.8),
                    ),
                ),
            ),
            weapons = listOf(weapon(level = 40)),
            characters = listOf(character(level = 50)),
        )

        val events = SnapshotActivityDetector().detect(
            previous,
            current,
            Instant.parse("2026-07-04T12:00:00Z"),
        )

        assertEquals(
            listOf(
                SnapshotActivityType.ARTIFACT_LEVEL,
                SnapshotActivityType.WEAPON_LEVEL,
                SnapshotActivityType.CHARACTER_LEVEL,
                SnapshotActivityType.MATERIAL_GAIN,
                SnapshotActivityType.MATERIAL_SPEND,
            ),
            events.map { it.type },
        )
        assertEquals(250, events.first { it.type == SnapshotActivityType.MATERIAL_GAIN }.amount)
        assertEquals(
            "mora",
            events.first { it.type == SnapshotActivityType.MATERIAL_GAIN }.materialKey,
        )
        assertEquals(8, events.first().currentLevel)
    }

    @Test
    fun `ignores equipment location and lock changes`() {
        val previous = snapshot(
            artifacts = listOf(artifact(level = 20, location = "Furina", locked = false)),
            weapons = listOf(weapon(level = 90, location = "Furina", locked = false)),
        )
        val current = snapshot(
            artifacts = listOf(artifact(level = 20, location = "Yelan", locked = true)),
            weapons = listOf(weapon(level = 90, location = "Yelan", locked = true)),
        )

        assertTrue(SnapshotActivityDetector().detect(previous, current).isEmpty())
    }

    @Test
    fun `persists recent activity and limits one snapshot to ten entries`() {
        val properties = GenshinContentProperties().also {
            it.cacheDirectory = cacheDirectory.toString()
        }
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val service = SnapshotActivityService(properties, mapper)
        val previous = snapshot(
            inventory = (1..15).associate { "material$it" to 0L },
        )
        val current = snapshot(
            inventory = (1..15).associate { "material$it" to it.toLong() },
        )

        service.record(7, previous, current)

        val reloaded = SnapshotActivityService(properties, mapper).recent(7)
        assertEquals(10, reloaded.size)
        assertTrue(reloaded.all { it.type == SnapshotActivityType.MATERIAL_GAIN })
        assertEquals(15, reloaded.first().amount)
    }

    private fun snapshot(
        inventory: Map<String, Long> = emptyMap(),
        artifacts: List<PlayerArtifact> = emptyList(),
        weapons: List<PlayerWeapon> = emptyList(),
        characters: List<PlayerCharacterState> = emptyList(),
    ) = PlayerSnapshot(
        formatVersion = 3,
        source = "Irminsul",
        importedAt = Instant.parse("2026-07-04T10:00:00Z"),
        characters = characters,
        inventory = inventory,
        inventoryNames = inventory.keys.associateWith { it.replaceFirstChar(Char::uppercase) },
        exportedInventoryKeys = inventory.size,
        artifacts = artifacts,
        weapons = weapons,
    )

    private fun artifact(
        level: Int,
        location: String?,
        locked: Boolean = false,
        substats: List<PlayerArtifactStat> = listOf(PlayerArtifactStat("critRate_", 3.1)),
    ) = PlayerArtifact(
        setKey = "GoldenTroupe",
        slotKey = "flower",
        level = level,
        rarity = 5,
        mainStatKey = "hp",
        location = location,
        locked = locked,
        substats = substats,
        totalRolls = substats.size,
        astralMark = false,
        elixirCrafted = false,
    )

    private fun weapon(
        level: Int,
        location: String? = "Furina",
        locked: Boolean = false,
    ) = PlayerWeapon(
        key = "SplendorOfTranquilWaters",
        level = level,
        ascension = if (level > 20) 2 else 1,
        refinement = 1,
        location = location,
        locked = locked,
    )

    private fun character(level: Int) = PlayerCharacterState(
        key = "Furina",
        level = level,
        constellation = 0,
        ascension = if (level > 40) 3 else 2,
        normalTalent = 1,
        skillTalent = 6,
        burstTalent = 6,
    )
}
