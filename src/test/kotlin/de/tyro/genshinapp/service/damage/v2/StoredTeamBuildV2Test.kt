package de.tyro.genshinapp.service.damage.v2

import de.tyro.genshinapp.service.catalog.EquipmentStatCatalog
import de.tyro.genshinapp.service.catalog.CharacterStatTestData
import de.tyro.genshinapp.service.catalog.CharacterCatalogStore
import de.tyro.genshinapp.service.catalog.WeaponCatalogStore
import de.tyro.genshinapp.service.importer.HoyolabCharacterPageService
import de.tyro.genshinapp.service.importer.HoyolabWeaponGalleryService
import de.tyro.genshinapp.service.player.PlayerSnapshotStore
import de.tyro.genshinapp.service.planning.CharacterTargetService
import de.tyro.genshinapp.service.planning.CharacterTargetValues
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerWeapon
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant

class StoredTeamBuildV2Test {
    private val snapshotStore = mock(PlayerSnapshotStore::class.java)
    private val targets = mock(CharacterTargetService::class.java)
    private val catalog = mock(EquipmentStatCatalog::class.java)
    private val characterCatalog = CharacterStatTestData.catalog()
    private val service = DamageTeamBuildServiceV2(snapshotStore, targets, mock(CharacterCatalogStore::class.java), mock(WeaponCatalogStore::class.java), catalog, characterCatalog,
        mock(HoyolabCharacterPageService::class.java), mock(HoyolabWeaponGalleryService::class.java))
    private val profile = DamageFormulaProfileV2(2, "nefer", "nefer", "Nefer", "dendro", "",
        emptyList(), emptyList(), emptyList(), teamMembers = listOf(DamageTeamMemberV2("nefer", "Nefer", "dendro", 1)), selectedTeam = setOf("nefer"))
    private fun snapshot() = PlayerSnapshot(1, "test", Instant.EPOCH,
        characters = listOf(PlayerCharacterState("Nefer", 90, 1, 6, 9, 10, 9)),
        inventory = emptyMap(), inventoryNames = emptyMap(), exportedInventoryKeys = 0,
        artifacts = emptyList(), weapons = emptyList())

    @Test
    fun `missing owned data is unavailable and cannot borrow another users snapshot`() {
        `when`(snapshotStore.current(1)).thenReturn(snapshot())
        val missing = service.builds(profile, 2).single()
        assertNull(missing.level)
        assertTrue(missing.characterStats.all { it.value == null })
        assertTrue(missing.totalStats.all { it.value == null })
        assertTrue(missing.issues.isNotEmpty())
        assertEquals(90, service.builds(profile, 1).single().level)
    }

    @Test
    fun `saved current progress takes precedence but manual bonuses and target levels do not`() {
        `when`(snapshotStore.current(1)).thenReturn(snapshot())
        `when`(targets.find(1, "nefer")).thenReturn(CharacterTargetValues(true, 80, 5, 2,
            9, 9, 9, mapOf("atk" to 9999.0, "eleMas" to 9999.0), 90, 6, 10, 10, 10))
        val build = service.builds(profile, 1).single()
        assertEquals(80, build.level)
        assertEquals(5, build.ascension)
        assertEquals(2, build.constellation)
        assertEquals(characterCatalog.resolve("nefer", 80, 5)!!["atk"], build.totalStats.first { it.key == "atk" }.value)
        assertEquals(0.0, build.totalStats.first { it.key == "eleMas" }.value)
    }

    @Test
    fun `unknown and duplicate weapons never produce a plausible partial total`() {
        val unknown = PlayerWeapon("unknown", 90, 6, 1, "Nefer", false)
        `when`(catalog.weapon("unknown", 90, 6)).thenReturn(null)
        `when`(snapshotStore.current(1)).thenReturn(snapshot().copy(weapons = listOf(unknown)))
        val missing = service.builds(profile, 1).single()
        assertTrue(missing.totalStats.all { it.value == null })
        assertTrue(missing.issues.any { it.contains("weapon stats") })
        val weapon = unknown.copy(key = "ReliquaryOfTruth")
        `when`(snapshotStore.current(1)).thenReturn(snapshot().copy(weapons = listOf(weapon, weapon)))
        val duplicate = service.builds(profile, 1).single()
        assertTrue(duplicate.totalStats.all { it.value == null })
        assertTrue(duplicate.issues.any { it.contains("Multiple weapons") })
    }

    @Test
    fun `exact Nefer values retain precision and add crit ascension once`() {
        val character = assertNotNull(characterCatalog.resolve("Nefer", 90, 6))
        assertEquals(344.4168692, character.getValue("atk"), 1e-7)
        assertEquals(12703.9082338, character.getValue("hp"), 1e-7)
        assertEquals(88.4, character.getValue("critDMG_"), 1e-8)
        assertEquals(5.0, character.getValue("critRate_"))
    }

    @Test
    fun `team character ascension stats are resolved from the respective source records`() {
        for (key in listOf("lauma", "nahida")) {
            assertEquals(115.2,
                characterCatalog.resolve(key, 90, 6)!!["eleMas"]!!, 1e-7)
        }
        assertEquals(24.2, characterCatalog.resolve("Columbina", 90, 6)!!["critRate_"]!!, 1e-7)
    }

    @Test
    fun `ascension boundaries use the saved phase without interpolation`() {
        val before = characterCatalog.resolve("nefer", 80, 5)!!
        val after = characterCatalog.resolve("nefer", 80, 6)!!
        assertTrue(after.getValue("atk") > before.getValue("atk"))
        assertEquals(78.8, before.getValue("critDMG_"), 1e-7)
        assertEquals(88.4, after.getValue("critDMG_"), 1e-7)
        assertNull(characterCatalog.resolve("nefer", 81, 5))
        assertNull(characterCatalog.resolve("nefer", 19, 1))
        assertNull(characterCatalog.resolve("nefer", 90, -1))
        assertNull(characterCatalog.resolve("not-a-character", 90, 6))
    }

    @Test
    fun `totals apply percentages to base including weapon then add artifact flat stats`() {
        val totals = StoredBuildTotalsV2.combine(
            mapOf("hp" to 10000.0, "atk" to 300.0, "def" to 600.0, "eleMas" to 115.2, "critRate_" to 5.0, "critDMG_" to 50.0, "enerRech_" to 100.0),
            mapOf("atk" to 500.0, "hp_" to 20.0),
            mapOf("atk_" to 50.0, "atk" to 311.0, "hp" to 4780.0, "def_" to 25.0, "eleMas" to 186.5, "critRate_" to 31.1),
        ).associateBy { it.key }
        assertEquals(1511.0, totals["atk"]!!.value)
        assertEquals(16780.0, totals["hp"]!!.value)
        assertEquals(750.0, totals["def"]!!.value)
        assertEquals(301.7, totals["eleMas"]!!.value)
        assertEquals(36.1, totals["critRate_"]!!.value)
        assertEquals(0.0, totals["dendro_dmg_"]!!.value)
        assertNotNull(totals["atk"]!!.formula)
    }

    @Test
    fun `unknown equipment is not silently counted as zero`() {
        assertTrue(StoredBuildTotalsV2.combine(mapOf("atk" to 300.0), null, emptyMap()).all { it.value == null })
        val totals = StoredBuildTotalsV2.combine(mapOf("atk" to 300.0), emptyMap(), mapOf("atk_" to null)).associateBy { it.key }
        assertNull(totals["atk"]!!.value)
        assertEquals(0.0, totals["eleMas"]!!.value)
    }
}
