package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.CharacterProgress
import de.tyro.genshinapp.model.CharacterTalentKind
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerWeapon
import de.tyro.genshinapp.model.TravelerAppearance
import java.nio.file.Files
import java.time.Duration
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CharacterCatalogServiceTest {
    private val objectMapper = jacksonObjectMapper()
    private val properties = GenshinContentProperties().also {
        it.cacheDirectory = Files.createTempDirectory("genshin-catalog-test").toString()
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

    init {
        materialCatalog.synchronizeCharacters(catalog.getCharacters())
    }

    @Test
    fun `loads the local character catalog`() {
        val furina = assertNotNull(catalog.findCharacter("furina"))

        assertTrue(catalog.getCharacters().size >= 80)
        assertEquals("Furina", furina.name)
        assertEquals("Hydro", furina.element)
        assertTrue(furina.ascensionCosts.isNotEmpty())
        assertTrue(furina.talentCosts.isNotEmpty())
        assertEquals(
            listOf(
                CharacterTalentKind.NORMAL_ATTACK,
                CharacterTalentKind.ELEMENTAL_SKILL,
                CharacterTalentKind.ELEMENTAL_BURST,
            ),
            furina.combatTalents.take(3).map { it.kind },
        )
        assertEquals("Salon Solitaire", furina.combatTalents[1].name)
        assertTrue(furina.passiveTalents.size >= 3)
        assertTrue(furina.talents.all { it.name.isNotBlank() && it.description.isNotBlank() })
    }

    @Test
    fun `loads every available talent kit from the local catalog`() {
        val charactersMissingTalents = catalog.getCharacters()
            .filterNot { it.key == "traveler" }
            .filter { it.talents.isEmpty() }

        assertTrue(
            charactersMissingTalents.isEmpty(),
            "Missing talent data for: ${charactersMissingTalents.joinToString { it.key }}",
        )
    }

    @Test
    fun `imports character and talent records from static genshin-db data idempotently`() {
        val character = objectMapper.readTree(
            """
                {
                  "id":999999,
                  "name":"Remote Hero",
                  "rarity":5,
                  "weaponText":"Sword",
                  "elementText":"Hydro",
                  "costs":{}
                }
            """.trimIndent(),
        )
        val talents = objectMapper.readTree(
            """
                {
                  "name":"Remote Hero",
                  "costs":{},
                  "combat1":{"name":"Remote Slash","description":"A remote normal attack."}
                }
            """.trimIndent(),
        )

        assertEquals(1, catalog.importFromStaticData(listOf(character), listOf(talents)))
        assertEquals(0, catalog.importFromStaticData(listOf(character), listOf(talents)))
        val imported = assertNotNull(catalog.findCharacter("remotehero"))
        assertEquals("Hydro", imported.element)
        assertEquals("Remote Slash", imported.talents.single().name)
    }

    @Test
    fun `exposes one canonical traveler character`() {
        val traveler = assertNotNull(catalog.findCharacter("traveler"))

        assertEquals("Traveler", traveler.name)
        assertEquals("traveler", traveler.key)
        assertEquals(1, catalog.getCharacters().count { it.key == "traveler" })
        assertTrue(catalog.getCharacters().none { it.key in setOf("aether", "lumine") })
        assertEquals("traveler", catalog.findCharacter("aether")?.key)
        assertEquals("traveler", catalog.findCharacter("lumine")?.key)
    }

    @Test
    fun `uses wikia defaults for traveler character images`() {
        val aether = catalog.findTravelerAppearance(TravelerAppearance.AETHER)
        val lumine = catalog.findTravelerAppearance(TravelerAppearance.LUMINE)

        val travelerImageUrls = CharacterImageType.entries.flatMap { imageType ->
            listOf(
                assertNotNull(aether.remoteImageUrl(imageType)),
                assertNotNull(lumine.remoteImageUrl(imageType)),
            )
        }

        assertTrue(travelerImageUrls.all { it.startsWith(WIKIA_IMAGE_PREFIX) })
        assertFalse(travelerImageUrls.any { it.startsWith(MIHOYO_IMAGE_PREFIX) })
        assertEquals(
            "https://static.wikia.nocookie.net/gensin-impact/images/1/1c/Traveler_Male_Card.jpg",
            aether.remoteImageUrl(CharacterImageType.CARD),
        )
        assertEquals(
            "https://static.wikia.nocookie.net/gensin-impact/images/c/c8/Traveler_Female_Card.jpg",
            lumine.remoteImageUrl(CharacterImageType.CARD),
        )
    }

    @Test
    fun `refreshes stale persisted traveler image defaults`() {
        val staleTraveler = assertNotNull(catalog.findTravelerAppearance(TravelerAppearance.AETHER))
            .copy(
                imageUrls = emptyMap(),
                remoteImageUrls = CharacterImageType.entries.associateWith {
                    "$MIHOYO_IMAGE_PREFIX/stale-${it.key}.png"
                },
            )
        val store = InMemoryCatalogStore(
            catalog.getCharacters().filterNot { it.key == "traveler" } + staleTraveler,
        )

        catalogWithStore(store)

        val refreshedTraveler = assertNotNull(store.findCharacter("traveler"))
        assertTrue(refreshedTraveler.remoteImageUrls.values.all { it.startsWith(WIKIA_IMAGE_PREFIX) })
        assertFalse(refreshedTraveler.remoteImageUrls.values.any { it.startsWith(MIHOYO_IMAGE_PREFIX) })
    }

    @Test
    fun `refreshes generated image defaults for persisted characters on startup`() {
        val staleFurina = assertNotNull(catalog.findCharacter("furina")).copy(
            imageUrls = emptyMap(),
            remoteImageUrls = CharacterImageType.entries.associateWith {
                "https://stale.example/${it.key}.png"
            },
        )
        val store = InMemoryCatalogStore(
            catalog.getCharacters().filterNot { it.key == "furina" } + staleFurina,
        )

        val restartedCatalog = catalogWithStore(store)
        val refreshedFurina = assertNotNull(restartedCatalog.findCharacter("furina"))

        CharacterImageType.entries.forEach { imageType ->
            assertEquals(
                fandomImageUrlResolver.characterImageUrl("Furina", imageType),
                refreshedFurina.remoteImageUrl(imageType),
            )
            assertNotNull(refreshedFurina.imageUrls[imageType])
        }
        assertTrue("furina" !in store.savedCharacterKeys)
    }

    @Test
    fun `formats talent scaling values for every available level`() {
        val ayaka = assertNotNull(catalog.findCharacter("kamisatoayaka"))
        val normalAttack = ayaka.combatTalents.first { it.kind == CharacterTalentKind.NORMAL_ATTACK }
        val skill = ayaka.combatTalents.first { it.kind == CharacterTalentKind.ELEMENTAL_SKILL }
        val specialMovement = ayaka.combatTalents.first {
            it.kind == CharacterTalentKind.SPECIAL_MOVEMENT
        }

        assertEquals("1-Hit DMG", normalAttack.attributes.first().label)
        assertEquals("45.7%", normalAttack.attributes.first().values.first())
        assertEquals("90.4%", normalAttack.attributes.first().values[9])
        assertEquals("239.2%", skill.attributes.first().values.first())
        assertEquals("10.0s", skill.attributes[1].values.first())
        assertEquals("10.0", specialMovement.attributes.first().values.single())
    }

    @Test
    fun `loads characters from the catalog store before bundled data`() {
        val storedCharacters = catalog.getCharacters().map { character ->
            if (character.key == "furina") {
                character.copy(name = "Stored Furina")
            } else {
                character
            }
        }
        val store = InMemoryCatalogStore(storedCharacters)
        val storedCatalog = catalogWithStore(store)

        val furina = assertNotNull(storedCatalog.findCharacter("furina"))

        assertEquals("Stored Furina", furina.name)
        assertTrue("furina" !in store.savedCharacterKeys)
    }

    @Test
    fun `saves bundled fallback characters into the catalog store`() {
        val store = InMemoryCatalogStore(
            catalog.getCharacters().filterNot { it.key == "albedo" },
        )

        catalogWithStore(store)

        assertTrue("albedo" in store.savedCharacterKeys)
        assertNotNull(store.findCharacter("albedo"))
    }

    @Test
    fun `refreshes persisted material metadata from stored character usage`() {
        val store = InMemoryCatalogStore(catalog.getCharacters())
        val materialStore = InMemoryMaterialCatalogStore()

        catalogWithStore(store, materialStore)

        assertEquals(MaterialCategory.COLLECTABLE, materialStore.findMaterial(100058)?.category)
        assertEquals(MaterialCategory.WEEKLY_BOSS, materialStore.findMaterial(113005)?.category)
        assertEquals(MaterialCategory.ENEMY_DROP, materialStore.findMaterial(112010)?.category)
        assertEquals(MaterialCategory.TALENT_BOOK, materialStore.findMaterial(104309)?.category)
    }

    @Test
    fun `calculates level and talent materials from the loaded data`() {
        val furina = assertNotNull(catalog.findCharacter("furina"))
        val materials = MaterialCalculator(materialCatalog).calculate(furina, CharacterProgress())

        assertEquals(168L, materials.first { it.name == "Lakelight Lily" }.amount)
        assertEquals(66L, materials.first { it.name == "Philosophies of Justice" }.amount)
        assertTrue(materials.none { it.name == "Crown of Insight" })
        assertTrue(materials.first { it.name == "Mora" }.amount > 1_000_000)
        assertTrue(materials.any { it.name == "Character EXP" })
    }

    @Test
    fun `calculates all supplied GOOD characters against shared inventory`() {
        val snapshot = GoodImportService(objectMapper).parse(
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        val plan = PlayerPlanningService(
            catalog,
            MaterialCalculator(materialCatalog),
            MaterialCraftingService(materialCatalog),
        ).createPlan(snapshot)
        val smallLampGrass = plan.aggregateMaterials.first { it.name == "Small Lamp Grass" }
        val characterExperience = plan.aggregateMaterials.first { it.name == "Character EXP" }
        val smallLampGrassNeeds = plan.characterNeeds(smallLampGrass.id)

        assertEquals(77, plan.characters.size)
        assertEquals(
            setOf("Jahoda", "Durin", "Columbina", "Illuga"),
            plan.unmatchedCharacterKeys.toSet(),
        )
        assertEquals(29L, smallLampGrass.owned)
        assertEquals(
            (smallLampGrass.required - 29L).coerceAtLeast(0L),
            smallLampGrass.missing,
        )
        assertEquals(smallLampGrass.required, smallLampGrassNeeds.sumOf { it.required })
        assertTrue(smallLampGrassNeeds.any { it.character.name == "Amber" })
        assertEquals(2_105_000L, characterExperience.owned)
    }

    @Test
    fun `aggregate plan respects saved ownership and individual character targets`() {
        val snapshot = GoodImportService(objectMapper).parse(
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        val furina = assertNotNull(snapshot.characters.find { it.key.equals("Furina", true) })
        val service = PlayerPlanningService(
            catalog,
            MaterialCalculator(materialCatalog),
            MaterialCraftingService(materialCatalog),
        )
        val targets = mapOf(
            "amber" to targetValues(owned = false),
            "furina" to targetValues(
                owned = true,
                state = furina,
                targetLevel = furina.level,
                targetAscension = maxOf(
                    furina.ascension,
                    CharacterProgress.minimumAscensionFor(furina.level),
                ),
                targetNormalTalent = furina.normalTalent,
                targetSkillTalent = furina.skillTalent,
                targetBurstTalent = furina.burstTalent,
            ),
        )

        val plan = service.createPlan(snapshot, targets)

        assertTrue(plan.characters.none { it.character.key == "amber" })
        assertTrue(
            assertNotNull(plan.characters.find { it.character.key == "furina" })
                .materials.isEmpty(),
        )
    }

    @Test
    fun `aggregate plan can include every catalog character with saved targets`() {
        val snapshot = GoodImportService(objectMapper).parse(
            Files.readAllBytes(GoodImportServiceTest.SAMPLE_EXPORT),
        )
        val unownedCharacter = assertNotNull(
            catalog.getCharacters().firstOrNull { character ->
                character.key != "traveler" && snapshot.characters.none { state ->
                    state.key.equals(character.key, ignoreCase = true)
                }
            },
        )
        val service = PlayerPlanningService(
            catalog,
            MaterialCalculator(materialCatalog),
            MaterialCraftingService(materialCatalog),
        )
        val targets = mapOf(
            unownedCharacter.key to targetValues(
                owned = false,
                targetLevel = 20,
                targetAscension = 0,
                targetNormalTalent = 1,
                targetSkillTalent = 1,
                targetBurstTalent = 1,
            ),
        )

        val ownedOnlyPlan = service.createPlan(snapshot, targets)
        val allCharactersPlan = service.createPlan(
            snapshot,
            targets,
            includeUnownedCharacters = true,
        )

        assertTrue(ownedOnlyPlan.characters.none { it.character.key == unownedCharacter.key })
        val included = assertNotNull(
            allCharactersPlan.characters.find { it.character.key == unownedCharacter.key },
        )
        val expected = MaterialCalculator(materialCatalog).calculate(
            unownedCharacter,
            CharacterProgress(
                owned = true,
                targetLevel = 20,
                targetAscension = 0,
                targetNormalTalent = 1,
                targetSkillTalent = 1,
                targetBurstTalent = 1,
            ),
        )
        assertEquals(1, included.state.level)
        assertEquals(
            expected.associate { it.id to it.amount },
            included.materials.associate { it.id to it.required },
        )
        assertTrue(allCharactersPlan.characters.size > ownedOnlyPlan.characters.size)
    }

    @Test
    fun `resolves ascension weapon and manual crit rate before artifacts`() {
        val weaponDataService = mock(WeaponDataService::class.java)
        `when`(weaponDataService.find("SkywardHarp")).thenReturn(
            WeaponDefinition(
                key = "skywardharp",
                name = "Skyward Harp",
                rarity = 5,
                secondaryStatType = "FIGHT_PROP_CRITICAL",
                baseSecondaryStat = 4.8,
                ascensionCosts = emptyMap(),
            ),
        )
        val service = OptimizerCombatStatService(catalog, weaponDataService)
        val character = PlayerCharacterState(
            key = "Furina",
            level = 90,
            constellation = 0,
            ascension = 6,
            normalTalent = 1,
            skillTalent = 1,
            burstTalent = 1,
        )
        val weapon = PlayerWeapon(
            key = "SkywardHarp",
            level = 90,
            ascension = 6,
            refinement = 1,
            location = "Furina",
            locked = true,
        )

        val stats = service.resolve(character, weapon, additionalCritRate = 7.5)

        assertEquals(24.2, stats.characterStats.getValue("critRate_"), 0.01)
        assertEquals(22.0512, stats.weaponStats.getValue("critRate_"), 0.01)
        assertEquals(7.5, stats.bonusStats.getValue("critRate_"), 0.01)
        assertEquals(53.7512, stats.totals.getValue("critRate_"), 0.01)
        assertEquals(50.0, stats.totals.getValue("critDMG_"), 0.01)
        assertEquals(100.0, stats.totals.getValue("enerRech_"), 0.01)
    }

    private fun catalogWithStore(
        store: CharacterCatalogStore,
        materialStore: MaterialCatalogStore = InMemoryMaterialCatalogStore(),
    ): CharacterCatalogService {
        val characterCatalog = CharacterCatalogService(
            objectMapper,
            DynamicContentLoader(
                objectMapper,
                properties,
                imageUrlRegistry,
                fandomImageUrlResolver,
            ),
            fandomImageUrlResolver,
            store,
        )
        MaterialCatalogService(objectMapper, materialStore)
            .synchronizeCharacters(characterCatalog.getCharacters())
        return characterCatalog
    }

    private fun targetValues(
        owned: Boolean,
        state: PlayerCharacterState? = null,
        targetLevel: Int = 80,
        targetAscension: Int = 6,
        targetNormalTalent: Int = 9,
        targetSkillTalent: Int = 9,
        targetBurstTalent: Int = 9,
    ) = CharacterTargetValues(
        owned = owned,
        currentLevel = state?.level,
        currentAscension = state?.ascension,
        currentConstellation = state?.constellation,
        currentNormalTalent = state?.normalTalent,
        currentSkillTalent = state?.skillTalent,
        currentBurstTalent = state?.burstTalent,
        additionalStats = emptyMap(),
        targetLevel = targetLevel,
        targetAscension = targetAscension,
        targetNormalTalent = targetNormalTalent,
        targetSkillTalent = targetSkillTalent,
        targetBurstTalent = targetBurstTalent,
    )

    private class InMemoryCatalogStore(
        characters: Collection<CharacterDefinition>,
    ) : CharacterCatalogStore {
        private val charactersByKey = linkedMapOf<String, CharacterDefinition>()
        val savedCharacterKeys = mutableListOf<String>()

        init {
            characters.forEach(::remember)
        }

        override fun getCharacters(): List<CharacterDefinition> =
            charactersByKey.values.sortedBy { it.name }

        override fun findCharacter(key: String): CharacterDefinition? =
            charactersByKey[key.trim().lowercase()]

        override fun saveCharacter(character: CharacterDefinition): CharacterDefinition {
            savedCharacterKeys += character.key
            remember(character)
            return character
        }

        private fun remember(character: CharacterDefinition) {
            charactersByKey[character.key] = character
        }
    }

    private class InMemoryMaterialCatalogStore : MaterialCatalogStore {
        private val materialsById = linkedMapOf<Int, MaterialDefinition>()

        override fun getMaterials(): List<MaterialDefinition> = materialsById.values.toList()

        override fun getMaterialsByIds(ids: Collection<Int>): List<MaterialDefinition> =
            ids.mapNotNull(materialsById::get)

        override fun getMaterialsByCategories(
            categories: Collection<MaterialCategory>,
        ): List<MaterialDefinition> = materialsById.values.filter { it.category in categories }

        override fun findMaterial(id: Int): MaterialDefinition? = materialsById[id]

        override fun saveMaterials(materials: Collection<MaterialDefinition>) {
            materials.forEach { material -> materialsById[material.id] = material }
        }

        override fun ensureSources(sources: Collection<MaterialSourceSeed>) = Unit

        override fun getSources(
            types: Collection<de.tyro.genshinapp.model.MaterialSourceType>,
        ) = emptyList<de.tyro.genshinapp.model.MaterialSourceDefinition>()
    }

    private companion object {
        private const val WIKIA_IMAGE_PREFIX = "https://static.wikia.nocookie.net"
        private const val MIHOYO_IMAGE_PREFIX = "https://upload-os-bbs.mihoyo.com"
    }
}
