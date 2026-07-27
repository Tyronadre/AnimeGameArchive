package de.tyro.genshinapp.service

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.MaterialSourceDefinition
import de.tyro.genshinapp.model.MaterialSourceType
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import java.util.concurrent.ConcurrentHashMap

@Service
class MaterialCatalogService(
    private val objectMapper: ObjectMapper,
    private val catalogStore: MaterialCatalogStore? = null,
    private val characterCatalogService: CharacterCatalogService? = null,
    private val contentLoader: DynamicContentLoader? = null,
) {
    private val materialsById = ConcurrentHashMap<Int, MaterialDefinition>()

    @Volatile
    private var synchronizedCharacterSignature: Int? = null

    init {
        catalogStore?.getMaterials().orEmpty().forEach(::remember)
        BASE_MATERIALS.forEach(::rememberDiscovered)
        loadBundledWeaponMaterials().forEach(::rememberDiscovered)
        synchronizeAvailableCharacters()
    }

    fun getMaterials(): List<MaterialDefinition> {
        synchronizeAvailableCharacters()
        return materialsById.values.sortedBy(MaterialDefinition::name)
    }

    fun getMaterialsByIds(ids: Collection<Int>): List<MaterialDefinition> {
        synchronizeAvailableCharacters()
        if (ids.isEmpty()) return emptyList()
        val stored = catalogStore?.getMaterialsByIds(ids).orEmpty()
        stored.forEach(::remember)
        return ids.mapNotNull(materialsById::get).distinctBy(MaterialDefinition::id)
    }

    fun getMaterialsByCategories(
        categories: Collection<MaterialCategory>,
    ): List<MaterialDefinition> {
        synchronizeAvailableCharacters()
        if (categories.isEmpty()) return emptyList()
        val stored = catalogStore?.getMaterialsByCategories(categories).orEmpty()
        stored.forEach(::remember)
        return materialsById.values
            .filter { it.category in categories }
            .sortedBy(MaterialDefinition::name)
    }

    fun findMaterial(id: Int): MaterialDefinition? {
        synchronizeAvailableCharacters()
        return materialsById[id] ?: catalogStore?.findMaterial(id)?.also(::remember)
    }

    fun getSources(types: Collection<MaterialSourceType>): List<MaterialSourceDefinition> {
        synchronizeAvailableCharacters()
        return catalogStore?.getSources(types).orEmpty()
    }

    @Synchronized
    fun synchronizeCharacters(characters: Collection<CharacterDefinition>) {
        materialsOf(characters).forEach(::rememberDiscovered)
        val enriched = MaterialCatalogMetadata.enrich(materialsById.values, characters)
        enriched.forEach { candidate ->
            val existing = materialsById[candidate.id]
            remember(
                if (existing == null || MaterialCatalogMetadata.isNonCraftableSpecial(candidate.id)) {
                    candidate
                } else existing.copy(
                    name = existing.name.ifBlank { candidate.name },
                    category = existing.category.takeUnless { it == MaterialCategory.OTHER }
                        ?: candidate.category,
                    craftingFamily = existing.craftingFamily ?: candidate.craftingFamily,
                    craftingTier = existing.craftingTier ?: candidate.craftingTier,
                    conversionGroup = existing.conversionGroup ?: candidate.conversionGroup,
                )
            )
        }
        catalogStore?.saveMaterials(materialsById.values)
        catalogStore?.ensureSources(MaterialCatalogSeed.sources)
        synchronizedCharacterSignature = characterSignature(characters)
        contentLoader?.registerDefaultImageLinks(emptyList(), materialsById.values.toList())
    }

    fun materialsOf(characters: Collection<CharacterDefinition>): List<MaterialDefinition> =
        characters.asSequence()
            .flatMap { character ->
                (character.ascensionCosts.values.flatten() +
                    character.talentCosts.values.flatten()).asSequence()
            }
            .filter { it.id > 0 }
            .distinctBy { it.id }
            .map { MaterialDefinition(it.id, it.name) }
            .sortedBy(MaterialDefinition::name)
            .toList()

    fun materialImageUrl(id: Int): String? {
        if (id < 0) return null
        return UriComponentsBuilder.fromPath("/media/materials/{id}")
            .buildAndExpand(id)
            .encode()
            .toUriString()
    }

    private fun rememberDiscovered(material: MaterialDefinition) {
        materialsById.compute(material.id) { _, existing -> existing ?: material }
    }

    private fun remember(material: MaterialDefinition) {
        materialsById[material.id] = material
    }

    private fun synchronizeAvailableCharacters() {
        val characters = characterCatalogService?.getCharacters() ?: return
        if (synchronizedCharacterSignature == characterSignature(characters)) return
        synchronizeCharacters(characters)
    }

    private fun characterSignature(characters: Collection<CharacterDefinition>): Int =
        characters.map { character ->
            listOf(
                character.key,
                character.ascensionCosts.hashCode(),
                character.talentCosts.hashCode(),
            )
        }.hashCode()

    private fun loadBundledWeaponMaterials(): List<MaterialDefinition> =
        BUNDLED_WEAPON_KEYS.asSequence().flatMap { key ->
            val resource = ClassPathResource("data/weapons/data/$key.json")
            if (!resource.exists()) return@flatMap emptySequence()
            resource.inputStream.use(objectMapper::readTree).path("costs")
                .flatMap { phase ->
                    phase.map { cost ->
                        MaterialDefinition(cost.path("id").asInt(), cost.path("name").asText())
                    }
                }
                .asSequence()
        }.filter { it.id > 0 && it.name.isNotBlank() }.distinctBy { it.id }.toList()

    private companion object {
        private val BASE_MATERIALS = listOf(
            MaterialDefinition(0, "Character EXP"),
            MaterialDefinition(104013, "Mystic Enhancement Ore"),
        )
        private val BUNDLED_WEAPON_KEYS = listOf("rust", "sacrificialbow")
    }
}
