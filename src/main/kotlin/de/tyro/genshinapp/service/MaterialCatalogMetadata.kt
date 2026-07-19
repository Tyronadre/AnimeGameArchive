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
        val categories = materials.associate { material ->
            material.id to category(material, usage[material.id])
        }
        val enemyFamilies = mutableMapOf<Int, String>()
        characters.forEach { character ->
            val ids = (character.ascensionCosts.values.flatten() +
                character.talentCosts.values.flatten())
                .map { it.id }
                .filter { categories[it] == MaterialCategory.ENEMY_DROP }
                .distinct().sorted()
            if (ids.isNotEmpty()) ids.forEach { enemyFamilies[it] = "enemy:${ids.first()}" }
        }
        addConsecutiveEnemyFamilies(
            categories
                .filterValues { it == MaterialCategory.ENEMY_DROP }
                .keys
                .filterNot(enemyFamilies::containsKey),
            enemyFamilies,
        )
        val weeklyFamilies = usage.keys
            .filter { categories[it] == MaterialCategory.WEEKLY_BOSS }
            .sorted().chunked(3).filter { it.size == 3 }
            .flatMap { family -> family.map { it to "weekly:${family.first()}" } }.toMap()

        val preliminary = materials.map { material ->
            val materialCategory = categories[material.id] ?: material.category
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

    private fun category(
        material: MaterialDefinition,
        usage: Usage?,
    ): MaterialCategory {
        val usageCategory = usage?.let { category(material.id, it) }
            ?.takeUnless { it == MaterialCategory.OTHER }
        if (usageCategory != null) return usageCategory

        val identityCategory = categoryFromIdentity(material.id, material.name)
            .takeUnless { it == MaterialCategory.OTHER }
        if (identityCategory != null) return identityCategory

        return material.category
    }

    private fun category(id: Int, usage: Usage): MaterialCategory = when {
        id in GEM_RANGE && GEM_SUFFIXES.any { usage.name.endsWith(it, true) } -> MaterialCategory.GEM
        TALENT_PREFIXES.any { usage.name.startsWith(it, true) } -> MaterialCategory.TALENT_BOOK
        id in 112000..112999 && usage.ascension && usage.talent -> MaterialCategory.ENEMY_DROP
        id in 113000..113999 && usage.talent && !usage.ascension -> MaterialCategory.WEEKLY_BOSS
        id in 113000..113999 && usage.ascension -> MaterialCategory.WORLD_BOSS
        id in COLLECTABLE_RANGE && usage.ascension -> MaterialCategory.COLLECTABLE
        id in WEAPON_RANGE -> MaterialCategory.WEAPON_ASCENSION
        else -> MaterialCategory.OTHER
    }

    private fun categoryFromIdentity(id: Int, name: String): MaterialCategory = when {
        id in GEM_RANGE && GEM_SUFFIXES.any { name.endsWith(it, true) } -> MaterialCategory.GEM
        TALENT_PREFIXES.any { name.startsWith(it, true) } -> MaterialCategory.TALENT_BOOK
        id in ENEMY_DROP_RANGE -> MaterialCategory.ENEMY_DROP
        id in WEAPON_RANGE -> MaterialCategory.WEAPON_ASCENSION
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

    private fun addConsecutiveEnemyFamilies(
        ids: Collection<Int>,
        families: MutableMap<Int, String>,
    ) {
        val current = mutableListOf<Int>()

        fun flush() {
            if (current.isEmpty()) return
            val familyKey = "enemy:${current.first()}"
            current.forEach { id -> families[id] = familyKey }
            current.clear()
        }

        ids.sorted().forEach { id ->
            if (current.isNotEmpty() && (id != current.last() + 1 || current.size == 3)) {
                flush()
            }
            current += id
        }
        flush()
    }

    private data class Usage(val name: String, var ascension: Boolean = false, var talent: Boolean = false)

    private val WEAPON_RANGE = 114001..114999
    private val ENEMY_DROP_RANGE = 112000..112999
    private val COLLECTABLE_RANGE = 100000..101999
    private val GEM_RANGE = 104100..104999
    private val GEM_SUFFIXES = listOf(" Sliver", " Fragment", " Chunk", " Gemstone")
    private val TALENT_PREFIXES = listOf("Teachings of ", "Guide to ", "Philosophies of ")
    private val TIERED = setOf(
        MaterialCategory.GEM, MaterialCategory.TALENT_BOOK,
        MaterialCategory.WEAPON_ASCENSION, MaterialCategory.ENEMY_DROP,
    )
}
