package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.CharacterProgress
import de.tyro.genshinapp.model.InventoryMaterialBalance
import de.tyro.genshinapp.model.MaterialCategory
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    private val materialCatalog = MaterialCatalogService(objectMapper)
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
    private val service = MaterialCraftingService(materialCatalog)

    init {
        materialCatalog.synchronizeCharacters(catalog.getCharacters())
    }

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
    fun `brilliant diamonds remain traveler requirements but are not craftable gems`() {
        val fragment = material("Brilliant Diamond Fragment")
        val info = assertNotNull(service.infoFor(fragment.id))

        assertEquals(MaterialCategory.OTHER, info.category)
        assertNull(info.familyKey)
        assertNull(info.tier)
        assertNull(info.conversionGroup)
        assertTrue(
            materialCatalog.getMaterialsByCategories(setOf(MaterialCategory.GEM))
                .none { it.id in 104101..104104 },
        )

        val availability = service.inventoryAvailability(
            fragment.id,
            mapOf(
                "brilliantdiamondsliver" to 99L,
                "brilliantdiamondfragment" to 2L,
                "dustofazoth" to 999L,
            ),
        )
        assertEquals(2L, availability.owned)
        assertEquals(0L, availability.craftable)

        val traveler = assertNotNull(catalog.findCharacter("traveler"))
        val diamondRequirements = MaterialCalculator(materialCatalog)
            .calculate(traveler, CharacterProgress())
            .filter { it.name.startsWith("Brilliant Diamond ") }
        assertEquals(4, diamondRequirements.size)
        assertTrue(diamondRequirements.all { it.amount > 0 })
    }

    @Test
    fun `non-craftable boss drops retain their catalog category`() {
        val bossDrop = material("Water That Failed To Transcend")

        val result = service.applyCrafting(
            listOf(balance(bossDrop.id, bossDrop.name, required = 5)),
            emptyMap(),
        ).single()

        assertEquals(MaterialCategory.WORLD_BOSS, result.category)
    }

    @Test
    fun `classifies reported farmable materials as concrete persisted categories`() {
        assertEquals(MaterialCategory.COLLECTABLE, categoryOf("Cor Lapis"))
        assertEquals(MaterialCategory.COLLECTABLE, categoryOf("Noctilucous Jade"))
        assertEquals(MaterialCategory.COLLECTABLE, categoryOf("Starconch"))
        assertEquals(MaterialCategory.COLLECTABLE, categoryOf("Violetgrass"))
        assertEquals(MaterialCategory.COLLECTABLE, categoryOf("Wolfhook"))
        assertEquals(MaterialCategory.WEEKLY_BOSS, categoryOf("Dvalin's Sigh"))
        assertEquals(MaterialCategory.ENEMY_DROP, categoryOf("Forbidden Curse Scroll"))
        assertEquals(MaterialCategory.ENEMY_DROP, categoryOf("Ominous Mask"))
        assertEquals(MaterialCategory.TALENT_BOOK, categoryOf("Guide to Resistance"))
        assertEquals(MaterialCategory.TALENT_BOOK, categoryOf("Philosophies of Ballad"))
        assertEquals(MaterialCategory.TALENT_BOOK, categoryOf("Philosophies of Freedom"))
        assertEquals(MaterialCategory.TALENT_BOOK, categoryOf("Philosophies of Resistance"))
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

    @Test
    fun `all crafting metadata has complete and valid persisted relationships`() {
        val craftingMaterials = materialCatalog.getMaterials().filter { it.category in setOf(
            MaterialCategory.GEM,
            MaterialCategory.TALENT_BOOK,
            MaterialCategory.WEAPON_ASCENSION,
            MaterialCategory.ENEMY_DROP,
            MaterialCategory.WEEKLY_BOSS,
        ) }

        assertTrue(craftingMaterials.isNotEmpty())
        assertTrue(craftingMaterials.all { it.craftingFamily != null })
        craftingMaterials.filter { it.category != MaterialCategory.WEEKLY_BOSS }.forEach {
            assertNotNull(it.craftingTier, it.name)
        }
        craftingMaterials.groupBy { it.craftingFamily }.values.forEach { family ->
            val expectedSize = when (family.first().category) {
                MaterialCategory.GEM, MaterialCategory.WEAPON_ASCENSION -> 4
                else -> 3
            }
            assertEquals(expectedSize, family.size, family.first().craftingFamily)
        }
    }

    @Test
    fun `gems convert across elements at the same tier with dust of azoth`() {
        val target = material("Varunada Lazurite Chunk")
        val result = service.applyCrafting(
            listOf(balance(target.id, target.name, required = 2)),
            mapOf("agnidusagatechunk" to 3L, "dustofazoth" to 18L),
        ).single()

        assertEquals(2L, result.craftable)
        assertEquals(0L, result.missing)
    }

    private fun categoryOf(name: String): MaterialCategory =
        assertNotNull(service.infoFor(material(name).id)).category

    private fun material(name: String) =
        assertNotNull(materialCatalog.getMaterials().find { it.name == name })

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
