package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.model.*
import org.springframework.stereotype.Service

/** The single entry point for character stats: base + level growth + cumulative ascension bonus. */
@Service
class CharacterStatCatalog(private val characters: CharacterCatalogStore) {
    fun find(key: String): CharacterStatProfile? {
        val sourceKey = sourceKey(key)
        return characters.findCharacter(sourceKey)?.stats
            ?: if (sourceKey == "traveler") characters.findCharacter("traveleranemo")?.stats else null
    }

    fun resolve(key: String, level: Int, ascension: Int): Map<String, Double>? {
        val range = CharacterGrowthCurves.phaseLevels.getOrNull(ascension) ?: return null
        if (level !in range) return null
        val profile = find(key) ?: return null
        val bonuses = if (ascension == 0) emptyMap() else profile.ascensionBonuses[ascension] ?: return null
        val result = (DEFAULT_STATS + profile.baseStats).toMutableMap()
        for (stat in CharacterGrowthCurves.growingStats) {
            val base = profile.baseStats[stat] ?: return null
            val growth = levelGrowth(profile, stat, level) ?: return null
            result[stat] = base + growth
        }
        bonuses.forEach { (stat, bonus) -> result[stat] = result.getOrDefault(stat, 0.0) + bonus }
        return result.mapKeys { it.key.optimizerKey }.takeIf { it.values.all(Double::isFinite) }
    }

    private fun levelGrowth(profile: CharacterStatProfile, stat: FightProperty, level: Int): Double? {
        if (level == 1) return 0.0
        profile.levelGrowth[level]?.get(stat)?.let { return it }
        val curve = profile.growthCurves[stat] ?: return null
        val lower = profile.levelGrowth.keys.filter { it < level }.maxOrNull() ?: 1
        val upper = profile.levelGrowth.keys.filter { it > level }.minOrNull() ?: return null
        val start = if (lower == 1) 0.0 else profile.levelGrowth[lower]?.get(stat) ?: return null
        val end = profile.levelGrowth[upper]?.get(stat) ?: return null
        val from = CharacterGrowthCurves.multiplier(curve, lower) ?: return null
        val to = CharacterGrowthCurves.multiplier(curve, upper) ?: return null
        val current = CharacterGrowthCurves.multiplier(curve, level) ?: return null
        if (to <= from) return null
        // Calibrate between rounded wiki checkpoints; don't amplify rounding of level-one ATK.
        return start + (end - start) * (current - from) / (to - from)
    }

    companion object {
        val DEFAULT_STATS = mapOf(
            FightProperty.FIGHT_PROP_CRITICAL to 5.0,
            FightProperty.FIGHT_PROP_CRITICAL_HURT to 50.0,
            FightProperty.FIGHT_PROP_CHARGE_EFFICIENCY to 100.0,
            FightProperty.FIGHT_PROP_ELEMENT_MASTERY to 0.0,
        )

        fun sourceKey(key: String): String = GoodKeyNormalizer.normalize(key).let {
            if (it == "aether" || it == "lumine" ||
                TravelerElement.entries.any { element -> element.variantKey == it }
            ) "traveler" else it
        }
    }
}
