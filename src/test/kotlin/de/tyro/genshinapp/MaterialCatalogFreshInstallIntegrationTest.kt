package de.tyro.genshinapp

import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialSourceType
import de.tyro.genshinapp.repository.MaterialRepository
import de.tyro.genshinapp.service.content.ImageUrlRegistry
import de.tyro.genshinapp.service.importer.HoyolabMaterialSummary
import de.tyro.genshinapp.service.importer.HoyolabPageParser
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.docker.compose.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:fresh-material-catalog;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "genshin.content.hoyolab-catalog-sync-enabled=false",
        "genshin.content.cache-directory=build/test-cache/fresh-material-catalog",
    ],
)
class MaterialCatalogFreshInstallIntegrationTest @Autowired constructor(
    private val materialCatalogService: MaterialCatalogService,
    private val materialRepository: MaterialRepository,
    private val imageUrlRegistry: ImageUrlRegistry,
    private val objectMapper: ObjectMapper,
) {
    @Test
    fun `fresh database contains complete source groups with authoritative categories`() {
        val sources = materialCatalogService.getSources(MaterialSourceType.entries)
        val forsakenRift = sources.first { it.key == "talent_domain:forsakenrift" }
        val stormterror = sources.first { it.key == "weekly_boss:stormterror" }
        val anemoHypostasis = sources.first { it.key == "world_boss:anemohypostasis" }

        assertEquals(9, forsakenRift.materials.size)
        assertEquals(15, stormterror.materials.size)
        assertEquals(5, anemoHypostasis.materials.size)

        assertCategory(104301, MaterialCategory.TALENT_BOOK)
        assertCategory(113001, MaterialCategory.WORLD_BOSS)
        assertCategory(113003, MaterialCategory.WEEKLY_BOSS)
        assertCategory(104112, MaterialCategory.GEM)
        assertCategory(112081, MaterialCategory.ENEMY_DROP)
        assertCategory(104102, MaterialCategory.OTHER)
        val gems = materialRepository.findAllByTypeInOrderByNameAsc(listOf(MaterialCategory.GEM.name))
        assertEquals(28, gems.size)
        assertTrue(gems.none { it.name.contains("Brilliant Diamond") })
        assertTrue(gems.none { it.name == "Transoceanic Chunk" })
    }

    @Test
    fun `hoyolab page references resolve to canonical game ids and official images`() {
        materialCatalogService.synchronizeHoyolabMaterialList(
            listOf(
                HoyolabMaterialSummary(
                    entryPageId = 164,
                    name = "Teachings of Freedom",
                    iconUrl = "https://bbs.hoyolab.com/hoyowiki/picture/object/Teachings%20of%20Freedom_icon.png",
                    category = MaterialCategory.TALENT_BOOK,
                ),
            ),
        )
        val bareReference = HoyolabPageParser(objectMapper).materialReferences(
            objectMapper.nodeFactory.textNode("\$[{\"ep_id\":164,\"amount\":3}]\$"),
        ).single()

        val cost = materialCatalogService.canonicalCost(bareReference)

        assertEquals(104301, cost.id)
        assertEquals("Teachings of Freedom", cost.name)
        assertEquals(3, cost.count)
        assertTrue(
            imageUrlRegistry.materialLink(104301)?.effectiveUrl.orEmpty()
                .startsWith("https://bbs.hoyolab.com/"),
        )
    }

    private fun assertCategory(gameId: Int, expected: MaterialCategory) {
        val material = assertNotNull(materialRepository.findByGameId(gameId))
        assertEquals(expected.name, material.type, material.name)
    }
}
