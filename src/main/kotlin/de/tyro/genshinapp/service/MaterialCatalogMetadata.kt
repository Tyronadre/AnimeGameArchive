package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialDefinition

/** Builds the metadata that is persisted with a material. Crafting code never has to infer it. */
object MaterialCatalogMetadata {
    fun enrich(
        materials: Collection<MaterialDefinition>,
        characters: Collection<CharacterDefinition>,
    ): List<MaterialDefinition> {
        val usage = linkedMapOf<Int, Usage>()
        characters.forEach { character ->
            character.ascensionCosts.values.flatten().forEach { cost ->
                usage.getOrPut(cost.id) { Usage(cost.name) }.ascension = true
            }
            character.talentCosts.values.flatten().forEach { cost ->
                usage.getOrPut(cost.id) { Usage(cost.name) }.talent = true
            }
        }
        val categories = usage.mapValues { (id, value) -> category(id, value) }
        val enemyFamilies = mutableMapOf<Int, String>()
        characters.forEach { character ->
            val ids = (character.ascensionCosts.values.flatten() +
                character.talentCosts.values.flatten())
                .map { it.id }
                .filter { categories[it] == MaterialCategory.ENEMY_DROP }
                .distinct().sorted()
            if (ids.isNotEmpty()) ids.forEach { enemyFamilies[it] = "enemy:${ids.first()}" }
        }
        val weeklyFamilies = usage.keys
            .filter { categories[it] == MaterialCategory.WEEKLY_BOSS }
            .sorted().chunked(3).filter { it.size == 3 }
            .flatMap { family -> family.map { it to "weekly:${family.first()}" } }.toMap()

        val preliminary = materials.map { material ->
            val value = usage[material.id]
            val materialCategory = value?.let { category(material.id, it) }
                ?: if (material.id in WEAPON_RANGE) MaterialCategory.WEAPON_ASCENSION
                else material.category
            val family = when (materialCategory) {
                MaterialCategory.GEM -> "gem:${familyWithoutSuffix(material.name, GEM_SUFFIXES)}"
                MaterialCategory.TALENT_BOOK -> "talent:${familyWithoutPrefix(material.name)}"
                MaterialCategory.ENEMY_DROP -> enemyFamilies[material.id]
                MaterialCategory.WEEKLY_BOSS -> weeklyFamilies[material.id]
                MaterialCategory.WEAPON_ASCENSION ->
                    "weapon:${(material.id - WEAPON_RANGE.first) / 4}"
                else -> null
            }
            material.copy(
                category = materialCategory,
                craftingFamily = family,
                conversionGroup = when (materialCategory) {
                    MaterialCategory.GEM -> "gem-tier"
                    MaterialCategory.WEEKLY_BOSS -> family
                    else -> null
                },
            )
        }
        val tiers = preliminary.filter { it.category in TIERED && it.craftingFamily != null }
            .groupBy { it.craftingFamily }
            .flatMap { (_, family) -> family.sortedBy { it.id }.mapIndexed { tier, item -> item.id to tier } }
            .toMap()
        return preliminary.map { it.copy(craftingTier = tiers[it.id]) }
    }

    private fun category(id: Int, usage: Usage): MaterialCategory = when {
        id in GEM_RANGE && GEM_SUFFIXES.any { usage.name.endsWith(it, true) } -> MaterialCategory.GEM
        TALENT_PREFIXES.any { usage.name.startsWith(it, true) } -> MaterialCategory.TALENT_BOOK
        id in 112000..112999 && usage.ascension && usage.talent -> MaterialCategory.ENEMY_DROP
        id in 113000..113999 && usage.talent && !usage.ascension -> MaterialCategory.WEEKLY_BOSS
        id in 113000..113999 && usage.ascension -> MaterialCategory.WORLD_BOSS
        id in 101000..101999 && usage.ascension -> MaterialCategory.COLLECTABLE
        else -> MaterialCategory.OTHER
    }

    private fun familyWithoutSuffix(name: String, suffixes: List<String>): String {
        val suffix = suffixes.first { name.endsWith(it, true) }
        return GoodKeyNormalizer.normalize(name.dropLast(suffix.length))
    }

    private fun familyWithoutPrefix(name: String): String {
        val prefix = TALENT_PREFIXES.first { name.startsWith(it, true) }
        return GoodKeyNormalizer.normalize(name.drop(prefix.length))
    }

    private data class Usage(val name: String, var ascension: Boolean = false, var talent: Boolean = false)

    private val WEAPON_RANGE = 114001..114999
    private val GEM_RANGE = 104100..104999
    private val GEM_SUFFIXES = listOf(" Sliver", " Fragment", " Chunk", " Gemstone")
    private val TALENT_PREFIXES = listOf("Teachings of ", "Guide to ", "Philosophies of ")
    private val TIERED = setOf(
        MaterialCategory.GEM, MaterialCategory.TALENT_BOOK,
        MaterialCategory.WEAPON_ASCENSION, MaterialCategory.ENEMY_DROP,
    )
}
