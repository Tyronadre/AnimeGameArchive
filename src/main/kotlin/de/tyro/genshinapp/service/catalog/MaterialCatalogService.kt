package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.service.content.ImageUrlRegistry
import de.tyro.genshinapp.service.content.MaterialImageDefault
import de.tyro.genshinapp.service.importer.HoyolabMaterialReference
import de.tyro.genshinapp.service.importer.HoyolabMaterialSummary
import de.tyro.genshinapp.startup.ApplicationStartupStatus
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.MaterialSourceDefinition
import de.tyro.genshinapp.model.MaterialSourceType
import org.springframework.stereotype.Service
import org.springframework.web.util.UriComponentsBuilder
import java.util.concurrent.ConcurrentHashMap

@Service
class MaterialCatalogService(
    private val catalogStore: MaterialCatalogStore? = null,
    private val characterCatalogService: CharacterCatalogService? = null,
    private val imageUrlRegistry: ImageUrlRegistry? = null,
    private val identityCatalog: MaterialIdentityCatalog? = null,
) {
    private val materialsById = ConcurrentHashMap<Int, MaterialDefinition>()
    private val materialIdByHoyolabPageId = ConcurrentHashMap<Int, Int>()

    @Volatile
    private var synchronizedCharacterSignature: Int? = null

    init {
        ApplicationStartupStatus.report(
            "Loading saved materials…",
            "Preparing inventory and progression material records",
        )
        catalogStore?.getMaterials().orEmpty().forEach(::remember)
        identityCatalog?.materials().orEmpty().forEach(::rememberIdentity)
        BASE_MATERIALS.forEach(::rememberDiscovered)
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

    @Synchronized
    fun synchronizeHoyolabMaterialList(entries: Collection<HoyolabMaterialSummary>): Int {
        var changed = 0
        val imageDefaults = mutableListOf<MaterialImageDefault>()
        entries.forEach { entry ->
            val pageId = entry.entryPageId.toInt().takeIf { it > 0 } ?: return@forEach
            val id = canonicalMaterialId(pageId, entry.name)
            materialIdByHoyolabPageId[pageId] = id
            val current = materialsById[id]
            val candidate = (current ?: MaterialDefinition(id, entry.name)).copy(
                name = entry.name,
                category = current?.category?.takeUnless { it == MaterialCategory.OTHER }
                    ?: entry.category.takeUnless { it == MaterialCategory.OTHER }
                    ?: MaterialCategory.OTHER,
            )
            if (current != candidate) {
                remember(candidate)
                changed++
            }
            entry.iconUrl?.let { imageDefaults += MaterialImageDefault(id, entry.name, it) }
        }
        normalizeAndPersist(characterCatalogService?.getCharacters().orEmpty())
        imageUrlRegistry?.registerMaterialDefaults(imageDefaults)
        return changed
    }

    @Synchronized
    fun synchronizeHoyolabReferences(references: Collection<HoyolabMaterialReference>) {
        val imageDefaults = mutableListOf<MaterialImageDefault>()
        val discovered = mutableListOf<MaterialDefinition>()
        // Resolve named references first so bare references to the same page use its canonical game id.
        val resolvedReferences = references.sortedBy { it.name == null }.map(::canonicalReference)
            .distinctBy(HoyolabMaterialReference::id)
        resolvedReferences.forEach { reference ->
            val current = materialsById[reference.id] ?: catalogStore?.findMaterial(reference.id)
            val name = reference.name ?: current?.name ?: "HoYoWiki material ${reference.hoyolabPageId}"
            if (current == null) {
                MaterialDefinition(reference.id, name).also {
                    remember(it)
                    discovered += it
                }
            }
            reference.imageUrl?.let {
                imageDefaults += MaterialImageDefault(
                    reference.id,
                    current?.name ?: name,
                    it,
                )
            }
        }
        if (discovered.isNotEmpty()) catalogStore?.saveMaterials(discovered)
        imageUrlRegistry?.registerMaterialDefaults(imageDefaults)
    }

    fun canonicalCost(reference: HoyolabMaterialReference) = canonicalReference(reference).toCost()

    /** Refreshes the source-controlled game-id bridge before a live HoYoWiki import. */
    @Synchronized
    fun refreshIdentityCatalog(): Boolean {
        val catalog = identityCatalog ?: return false
        if (!catalog.refreshRemote()) return false
        catalog.materials().forEach(::rememberIdentity)
        normalizeAndPersist(characterCatalogService?.getCharacters().orEmpty())
        return true
    }

    fun getSources(types: Collection<MaterialSourceType>): List<MaterialSourceDefinition> {
        synchronizeAvailableCharacters()
        return catalogStore?.getSources(types).orEmpty()
    }

    @Synchronized
    fun synchronizeCharacters(characters: Collection<CharacterDefinition>) {
        materialsOf(characters).forEach(::rememberDiscovered)
        val enriched = MaterialCatalogMetadata.enrich(materialsById.values, characters)
            .map(MaterialCatalogSeed::applySourceMetadata)
        enriched.forEach { candidate ->
            val existing = materialsById[candidate.id]
            remember(
                if (existing == null || MaterialCatalogMetadata.isNonCraftableSpecial(candidate.name)) {
                    candidate
                } else existing.copy(
                    name = existing.name.ifBlank { candidate.name },
                    category = candidate.category,
                    craftingFamily = candidate.craftingFamily,
                    craftingTier = candidate.craftingTier,
                    conversionGroup = candidate.conversionGroup,
                )
            )
        }
        catalogStore?.saveMaterials(materialsById.values)
        catalogStore?.ensureSources(MaterialCatalogSeed.sources)
        synchronizedCharacterSignature = characterSignature(characters)
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

    private fun rememberIdentity(material: MaterialDefinition) {
        materialsById.compute(material.id) { _, existing ->
            if (existing == null) material else existing.copy(
                name = material.name,
                category = material.category.takeUnless { it == MaterialCategory.OTHER }
                    ?: existing.category,
                craftingFamily = material.craftingFamily ?: existing.craftingFamily,
                craftingTier = material.craftingTier ?: existing.craftingTier,
                conversionGroup = material.conversionGroup ?: existing.conversionGroup,
            )
        }
    }

    private fun remember(material: MaterialDefinition) {
        materialsById[material.id] = material
    }

    private fun normalizeAndPersist(characters: Collection<CharacterDefinition>) {
        MaterialCatalogMetadata.enrich(materialsById.values, characters)
            .map(MaterialCatalogSeed::applySourceMetadata)
            .forEach(::remember)
        catalogStore?.saveMaterials(materialsById.values)
        catalogStore?.ensureSources(MaterialCatalogSeed.sources)
    }

    private fun canonicalReference(reference: HoyolabMaterialReference): HoyolabMaterialReference {
        val id = materialIdByHoyolabPageId[reference.hoyolabPageId]
            ?: canonicalMaterialId(reference.hoyolabPageId, reference.name)
                .also { materialIdByHoyolabPageId[reference.hoyolabPageId] = it }
        val name = materialsById[id]?.name ?: reference.name
        return reference.copy(id = id, name = name)
    }

    private fun canonicalMaterialId(hoyolabPageId: Int, name: String?): Int =
        identityCatalog?.let { catalog -> name?.let(catalog::gameIdForName) }
            ?: materialIdByHoyolabPageId[hoyolabPageId]
            ?: Math.addExact(HOYOLAB_SYNTHETIC_ID_BASE, hoyolabPageId)

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

    private companion object {
        private const val HOYOLAB_SYNTHETIC_ID_BASE = 1_500_000_000
        private val BASE_MATERIALS = listOf(
            MaterialDefinition(0, "Character EXP"),
            MaterialDefinition(1327, "Mystic Enhancement Ore"),
        )
    }
}
