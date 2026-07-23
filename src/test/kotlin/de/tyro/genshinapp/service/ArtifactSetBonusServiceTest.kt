package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.repository.ArtifactSetBonusRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@DataJpaTest
class ArtifactSetBonusServiceTest(
    @Autowired private val repository: ArtifactSetBonusRepository,
) {
    private val objectMapper = jacksonObjectMapper()

    @BeforeEach
    fun clearBonuses() {
        repository.deleteAll()
    }

    @Test
    fun `seeds every artifact set once and keeps persisted calculations`() {
        val service = ArtifactSetBonusService(objectMapper, repository)

        service.initialize()

        assertEquals(61, repository.count())
        assertEquals(
            ArtifactCatalogService(objectMapper).allSets().keys,
            service.allDefinitions().mapTo(linkedSetOf()) { it.setKey },
        )
        val stored = assertNotNull(repository.findBySetKey("blizzardstrayer"))
        val firstUpdatedAt = stored.updatedAt

        service.initialize()

        assertEquals(61, repository.count())
        assertEquals(
            firstUpdatedAt,
            repository.findBySetKey("blizzardstrayer")?.updatedAt,
        )
    }

    @Test
    fun `loads active maximum set bonuses into optimizer build stats`() {
        val bonusService = ArtifactSetBonusService(objectMapper, repository).also {
            it.initialize()
        }
        val optimizationService = ArtifactOptimizationService(
            artifactCatalogService = ArtifactCatalogService(objectMapper),
            artifactSetBonusProvider = bonusService,
        )
        val artifacts = listOf(
            artifact("flower", "hp"),
            artifact("plume", "atk"),
            artifact("sands", "hp_"),
            artifact("circlet", "hp_"),
        )

        val stats = optimizationService.summarizeCurrentBuild(
            artifacts,
            OptimizerBaseStats.defaults(),
        )

        assertEquals(45.0, stats.totalValues.getValue("critRate_"), 0.001)
        assertEquals(15.0, stats.totalValues.getValue("cryo_dmg_"), 0.001)
        assertEquals(2, stats.activeSetBonuses.size)
        assertEquals(listOf(2, 4), stats.activeSetBonuses.map { it.pieces })
        assertNotNull(stats.activeSetBonuses.last().assumption)
        assertEquals(
            40.0,
            stats.rows.first { it.key == "critRate_" }.setBonusValue,
            0.001,
        )
    }

    private fun artifact(slotKey: String, mainStatKey: String): PlayerArtifact =
        PlayerArtifact(
            setKey = "BlizzardStrayer",
            slotKey = slotKey,
            level = 20,
            rarity = 5,
            mainStatKey = mainStatKey,
            location = null,
            locked = false,
            substats = emptyList(),
            totalRolls = 0,
            astralMark = false,
            elixirCrafted = false,
        )
}
