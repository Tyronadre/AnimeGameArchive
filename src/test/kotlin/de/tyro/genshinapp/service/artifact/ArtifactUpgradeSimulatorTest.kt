package de.tyro.genshinapp.service.artifact

import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerArtifactStat
import kotlin.random.Random
import kotlin.test.*

class ArtifactUpgradeSimulatorTest {
    private val starter = PlayerArtifact("GildedDreams", "flower", 0, 5, "hp", null, false,
        listOf(PlayerArtifactStat("eleMas", 23.31), PlayerArtifactStat("critRate_", 3.89), PlayerArtifactStat("critDMG_", 7.77)),
        3, false, false)

    @Test fun `missing fourth substat consumes a milestone and seed ignores ownership`() {
        val keys = mutableListOf<String>()
        val seed = ArtifactUpgradeSimulator.seed(starter)
        val result = ArtifactUpgradeSimulator.finish(starter, Random(seed), keys::add)
        assertEquals(20, result.level)
        assertEquals(4, result.substats.size)
        assertEquals(5, keys.size)
        assertTrue(keys.first() !in starter.substats.map { it.key })
        assertTrue(result.substats.none { it.key == starter.mainStatKey })
        assertEquals(8, result.totalRolls)
        assertEquals(seed, ArtifactUpgradeSimulator.seed(starter.copy(location = "Nefer", locked = true)))
        assertEquals(result, ArtifactUpgradeSimulator.finish(starter, Random(seed)))
        assertEquals(1, ArtifactUpgradeSimulator.remaining(starter.copy(level = 19)))
        assertEquals(0, ArtifactUpgradeSimulator.remaining(starter.copy(level = 20)))
    }

    @Test fun `projection rejects unsupported upgrades and malformed starters`() {
        assertNull(ArtifactUpgradeSimulator.unavailableReason(starter))
        assertEquals("specialUpgrades", ArtifactUpgradeSimulator.unavailableReason(starter.copy(elixirCrafted = true)))
        assertEquals("specialUpgrades", ArtifactUpgradeSimulator.unavailableReason(starter.copy(unactivatedSubstats = starter.substats)))
        assertEquals("fiveStarOnly", ArtifactUpgradeSimulator.unavailableReason(starter.copy(rarity = 4)))
        assertEquals("invalidArtifact", ArtifactUpgradeSimulator.unavailableReason(starter.copy(level = 8)))
        assertEquals("invalidArtifact", ArtifactUpgradeSimulator.unavailableReason(starter.copy(substats = starter.substats + starter.substats[0])))
    }
}
