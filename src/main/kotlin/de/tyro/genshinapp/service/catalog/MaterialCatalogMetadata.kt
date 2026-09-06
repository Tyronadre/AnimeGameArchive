package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialDefinition

/** Builds the metadata that is persisted with a material. Crafting code never has to infer it. */
object MaterialCatalogMetadata {
    fun isNonCraftableSpecial(name: String): Boolean =
        name.startsWith("Brilliant Diamond ", ignoreCase = true)

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
        val preliminary = materials.map { material ->
            val materialCategory = categories[material.id] ?: material.category
            val family = when (materialCategory) {
                MaterialCategory.GEM -> "gem:${familyWithoutSuffix(material.name, GEM_SUFFIXES)}"
                MaterialCategory.TALENT_BOOK -> "talent:${familyWithoutPrefix(material.name)}"
                MaterialCategory.ENEMY_DROP -> enemyFamilies[material.id]
                MaterialCategory.WEEKLY_BOSS -> null
                MaterialCategory.WEAPON_ASCENSION -> null
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
        if (isNonCraftableSpecial(material.name)) return MaterialCategory.OTHER

        val usageCategory = usage?.let(::category)
            ?.takeUnless { it == MaterialCategory.OTHER }
        if (usageCategory != null) return usageCategory

        val identityCategory = categoryFromIdentity(material.name)
            .takeUnless { it == MaterialCategory.OTHER }
        if (identityCategory != null) return identityCategory

        // These categories were previously inferred too broadly. Clear persisted false positives.
        if (material.category == MaterialCategory.GEM) return MaterialCategory.OTHER
        if (material.category == MaterialCategory.TALENT_BOOK) return MaterialCategory.OTHER
        return material.category
    }

    private fun category(usage: Usage): MaterialCategory = when {
        isElementalGem(usage.name) -> MaterialCategory.GEM
        TALENT_PREFIXES.any { usage.name.startsWith(it, true) } -> MaterialCategory.TALENT_BOOK
        usage.ascension && usage.talent && !usage.name.equals("Mora", ignoreCase = true) ->
            MaterialCategory.ENEMY_DROP
        usage.talent && !usage.name.equals("Crown of Insight", ignoreCase = true) ->
            MaterialCategory.WEEKLY_BOSS
        usage.ascension && !usage.name.equals("Mora", ignoreCase = true) ->
            MaterialCategory.COLLECTABLE
        else -> MaterialCategory.OTHER
    }

    private fun categoryFromIdentity(name: String): MaterialCategory = when {
        isElementalGem(name) -> MaterialCategory.GEM
        TALENT_PREFIXES.any { name.startsWith(it, true) } -> MaterialCategory.TALENT_BOOK
        else -> MaterialCategory.OTHER
    }

    private fun familyWithoutSuffix(name: String, suffixes: List<String>): String {
        val suffix = suffixes.first { name.endsWith(it, true) }
        return GoodKeyNormalizer.normalize(name.dropLast(suffix.length))
    }

    private fun isElementalGem(name: String): Boolean = ELEMENTAL_GEM_FAMILIES.any { family ->
        GEM_SUFFIXES.any { suffix -> name.equals(family + suffix, ignoreCase = true) }
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

    private val GEM_SUFFIXES = listOf(" Sliver", " Fragment", " Chunk", " Gemstone")
    private val ELEMENTAL_GEM_FAMILIES = listOf(
        "Agnidus Agate",
        "Varunada Lazurite",
        "Vajrada Amethyst",
        "Vayuda Turquoise",
        "Shivada Jade",
        "Prithiva Topaz",
        "Nagadus Emerald",
    )
    private val TALENT_PREFIXES = listOf("Teachings of ", "Guide to ", "Philosophies of ")
    private val TIERED = setOf(
        MaterialCategory.GEM, MaterialCategory.TALENT_BOOK,
        MaterialCategory.WEAPON_ASCENSION, MaterialCategory.ENEMY_DROP,
    )
}
