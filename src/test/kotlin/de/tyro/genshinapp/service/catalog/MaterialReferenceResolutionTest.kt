package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.service.importer.HoyolabMaterialReference
import org.mockito.Mockito.*
import kotlin.test.*

class MaterialReferenceResolutionTest {
    @Test
    fun `restart restores bare wiki references without downloading a catalog`() {
        val store = mock(MaterialCatalogStore::class.java)
        `when`(store.getMaterials()).thenReturn(listOf(MaterialDefinition(202, "Mora", hoyolabEntryId = 757)))
        val service = MaterialCatalogService(catalogStore = store)
        val bare = HoyolabMaterialReference(757, null, 12500)
        service.synchronizeHoyolabReferences(listOf(bare))
        val cost = service.canonicalCost(bare)
        assertEquals(202, cost.id)
        assertEquals("Mora", cost.name)
        assertEquals(12500L, cost.count)
        assertNull(service.findMaterial(1500000757))
    }

    @Test
    fun `a resolved name replaces an earlier fallback cached for the same page`() {
        val identities = mock(MaterialIdentityCatalog::class.java)
        `when`(identities.materials()).thenReturn(listOf(MaterialDefinition(202, "Mora")))
        `when`(identities.gameIdForName("Mora")).thenReturn(202)
        val service = MaterialCatalogService(identityCatalog = identities)
        val bare = HoyolabMaterialReference(757, null, 10)
        service.synchronizeHoyolabReferences(listOf(bare))
        assertEquals(1500000757, service.canonicalCost(bare).id)
        assertEquals(202, service.canonicalCost(bare.copy(name = "Mora")).id)
        assertEquals(202, service.canonicalCost(bare).id)
    }
}
