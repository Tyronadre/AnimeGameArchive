package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialSourceRole
import de.tyro.genshinapp.model.MaterialSourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaterialSourceCatalogTest {
    @Test
    fun `material allocation only exposes inventory beyond exact requirements`() {
        val item = MissingMaterialItem(
            id = 104302,
            name = "Guide to Freedom",
            required = 18,
            owned = 40,
            craftable = 0,
            needed = 0,
            imageUrl = null,
            category = MaterialCategory.TALENT_BOOK,
        )

        assertEquals(22, item.freeToUse)
        assertEquals(0, item.copy(owned = 10).freeToUse)
    }

    @Test
    fun `all talent domains contain three complete book families`() {
        val domains = MaterialCatalogSeed.sources.filter {
            it.type == MaterialSourceType.TALENT_DOMAIN
        }

        assertEquals(7, domains.size)
        assertTrue(domains.all { domain ->
            val families = domain.materials.groupBy { it.familyOrder }
            families.size == 3 && families.values.all { it.size == 3 }
        })

        val materialIds = domains.flatMap { domain -> domain.materials.map { it.materialId } }
        assertEquals(63, materialIds.distinct().size)
        assertEquals((104301..104318).toList(), materialIds.take(18))
        assertEquals((104356..104364).toList(), materialIds.takeLast(9))
    }

    @Test
    fun `weekly bosses expose exactly three unique drops`() {
        val drops = MaterialCatalogSeed.sources
            .filter { it.type == MaterialSourceType.WEEKLY_BOSS }
            .map { source -> source.materials.filter { it.role == MaterialSourceRole.DROP } }

        assertTrue(drops.all { it.size == 3 })
        val ids = drops.flatten().map { it.materialId }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `enemy sources expose complete three-tier families`() {
        val enemies = MaterialCatalogSeed.sources.filter { it.type == MaterialSourceType.ENEMY }

        assertTrue(enemies.all { source ->
            val ids = source.materials.map { it.materialId }
            ids.size == 3 && ids.zipWithNext().all { (first, second) -> second == first + 1 }
        })
    }

    @Test
    fun `every boss gem family contains all four tiers`() {
        val bosses = MaterialCatalogSeed.sources.filter {
            it.type == MaterialSourceType.WORLD_BOSS ||
                it.type == MaterialSourceType.WEEKLY_BOSS
        }

        assertTrue(bosses.all { boss ->
            val gemFamilies = boss.materials
                .filter { it.role == MaterialSourceRole.GEM }
                .groupBy { it.familyOrder }
            gemFamilies.isNotEmpty() && gemFamilies.values.all { it.size == 4 }
        })
    }
}
