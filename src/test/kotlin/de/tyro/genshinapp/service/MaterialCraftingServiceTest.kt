package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.InventoryMaterialBalance
import de.tyro.genshinapp.model.MaterialCategory
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MaterialCraftingServiceTest {
    private val objectMapper = jacksonObjectMapper()
    private val properties = GenshinContentProperties().also {
        it.cacheDirectory = Files.createTempDirectory("genshin-crafting-test").toString()
        it.characterApiUrl = "http://127.0.0.1:1"
        it.connectTimeout = Duration.ofMillis(100)
        it.requestTimeout = Duration.ofMillis(100)
    }
    private val imageUrlRegistry = ImageUrlRegistry(objectMapper, properties)
    private val fandomImageUrlResolver = FandomImageUrlResolver(properties)
    private val catalog = CharacterCatalogService(
        objectMapper,
        DynamicContentLoader(
            objectMapper,
            properties,
            imageUrlRegistry,
            fandomImageUrlResolver,
        ),
        fandomImageUrlResolver,
    )
    private val service = MaterialCraftingService(catalog)

    @Test
    fun `classifies all six character material categories`() {
        assertEquals(MaterialCategory.GEM, categoryOf("Varunada Lazurite Sliver"))
        assertEquals(MaterialCategory.TALENT_BOOK, categoryOf("Teachings of Justice"))
        assertEquals(MaterialCategory.ENEMY_DROP, categoryOf("Whopperflower Nectar"))
        assertEquals(MaterialCategory.COLLECTABLE, categoryOf("Lakelight Lily"))
        assertEquals(MaterialCategory.WEEKLY_BOSS, categoryOf("Lightless Mass"))
        assertEquals(MaterialCategory.WORLD_BOSS, categoryOf("Water That Failed To Transcend"))
    }

    @Test
    fun `calculates maximum tiered crafting from lower inventory levels`() {
        val fragment = material("Varunada Lazurite Fragment")
        val chunk = material("Varunada Lazurite Chunk")
        val inventory = mapOf("varunadalazuritesliver" to 13L)

        val fragmentAvailability = service.inventoryAvailability(fragment.id, inventory)
        val chunkAvailability = service.inventoryAvailability(chunk.id, inventory)

        assertEquals(4L, fragmentAvailability.craftable)
        assertEquals(4L, fragmentAvailability.available)
        assertEquals(1L, chunkAvailability.craftable)
        assertEquals(1L, chunkAvailability.available)
    }

    @Test
    fun `reserves lower tiers before crafting for higher requirements`() {
        val sliver = material("Varunada Lazurite Sliver")
        val fragment = material("Varunada Lazurite Fragment")
        val chunk = material("Varunada Lazurite Chunk")
        val balances = listOf(
            balance(sliver.id, sliver.name, required = 1),
            balance(fragment.id, fragment.name, required = 3),
            balance(chunk.id, chunk.name, required = 1),
        )

        val result = service.applyCrafting(
            balances,
            mapOf("varunadalazuritesliver" to 13L),
        )

        assertEquals(0L, result.first { it.id == sliver.id }.missing)
        assertEquals(4L, result.first { it.id == fragment.id }.craftable)
        assertEquals(0L, result.first { it.id == fragment.id }.missing)
        assertEquals(0L, result.first { it.id == chunk.id }.craftable)
        assertEquals(1L, result.first { it.id == chunk.id }.missing)
    }

    @Test
    fun `weekly boss conversion uses sibling drops and dream solvent`() {
        val sigh = material("Dvalin's Sigh")
        val result = service.applyCrafting(
            listOf(balance(sigh.id, sigh.name, required = 5)),
            mapOf(
                "dvalinsplume" to 3L,
                "dvalinsclaw" to 3L,
                "dvalinssigh" to 1L,
                "dreamsolvent" to 2L,
            ),
        ).single()

        assertEquals(1L, result.owned)
        assertEquals(2L, result.craftable)
        assertEquals(2L, result.missing)
    }

    private fun categoryOf(name: String): MaterialCategory =
        assertNotNull(service.infoFor(material(name).id)).category

    private fun material(name: String) =
        assertNotNull(catalog.getMaterials().find { it.name == name })

    private fun balance(
        id: Int,
        name: String,
        required: Long,
    ) = InventoryMaterialBalance(
        id = id,
        name = name,
        required = required,
        owned = 0,
        missing = required,
        imageUrl = null,
    )
}
