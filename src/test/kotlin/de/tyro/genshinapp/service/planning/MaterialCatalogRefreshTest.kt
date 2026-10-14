package de.tyro.genshinapp.service.planning

import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.service.catalog.MaterialCatalogChanged
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import org.mockito.Mockito.*
import kotlin.test.Test
import kotlin.test.assertEquals

class MaterialCatalogRefreshTest {
    @Test
    fun `crafting sees a new family imported after its cache was initialized`() {
        val catalog = mock(MaterialCatalogService::class.java)
        `when`(catalog.getMaterialsByCategories(anyCollection())).thenReturn(emptyList())
        val service = MaterialCraftingService(catalog)
        assertEquals(0L, service.inventoryAvailability(104302, emptyMap()).craftable)
        val books = listOf(
            MaterialDefinition(104301, "Teachings of Freedom", MaterialCategory.TALENT_BOOK, "talent:freedom", 0),
            MaterialDefinition(104302, "Guide to Freedom", MaterialCategory.TALENT_BOOK, "talent:freedom", 1),
        )
        `when`(catalog.getMaterialsByCategories(anyCollection())).thenReturn(books)
        service.onCatalogChanged(MaterialCatalogChanged())
        assertEquals(2L, service.inventoryAvailability(104302, mapOf("teachingsoffreedom" to 6L)).craftable)
    }
}
