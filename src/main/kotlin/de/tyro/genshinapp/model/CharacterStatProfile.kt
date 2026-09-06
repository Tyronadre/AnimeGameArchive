package de.tyro.genshinapp.model

/** Percentages are percentage points throughout the database and application. */
data class CharacterBaseValues(
    val hp: Double?, val attack: Double?, val defense: Double?,
    val critRate: Double?, val critDamage: Double?,
    val energyRecharge: Double?, val elementalMastery: Double?,
)

data class CharacterGrowthValues(val key: String, val multipliers: Map<Int, Double>)
data class CharacterAscensionValues(
    val phase: Int, val minLevel: Int, val maxLevel: Int,
    val hp: Double, val attack: Double, val defense: Double, val statBonus: Double,
)

data class CharacterStatProfile(
    val key: String,
    val base: CharacterBaseValues,
    val hpCurve: CharacterGrowthValues?, val attackCurve: CharacterGrowthValues?, val defenseCurve: CharacterGrowthValues?,
    val ascensionStat: FightProperty?, val ascensions: List<CharacterAscensionValues>,
    val sourceRevision: String, val sourceUrl: String,
    val hoyolabAscension: List<CharacterHoyolabAscension> = emptyList(),
) {
    fun resolve(level: Int, ascension: Int): Map<String, Double>? {
        if (hoyolabAscension.isNotEmpty()) return resolveHoyolab(level, ascension)
        val promotion = ascensions.singleOrNull { it.phase == ascension && level in it.minLevel..it.maxLevel } ?: return null
        val stats = linkedMapOf(
            "hp" to ((base.hp ?: return null) * (hpCurve?.multipliers?.get(level) ?: return null) + promotion.hp),
            "atk" to ((base.attack ?: return null) * (attackCurve?.multipliers?.get(level) ?: return null) + promotion.attack),
            "def" to ((base.defense ?: return null) * (defenseCurve?.multipliers?.get(level) ?: return null) + promotion.defense),
            "critRate_" to (base.critRate ?: return null), "critDMG_" to (base.critDamage ?: return null),
            "enerRech_" to (base.energyRecharge ?: return null), "eleMas" to (base.elementalMastery ?: return null),
        )
        val stat = ascensionStat?.optimizerKey ?: return null
        stats[stat] = stats.getOrDefault(stat, 0.0) + promotion.statBonus
        return stats.takeIf { it.values.all(Double::isFinite) }
    }

    private fun resolveHoyolab(level: Int, ascension: Int): Map<String, Double>? {
        val rows = hoyolabAscension.sortedBy(CharacterHoyolabAscension::level)
        val start = rows.getOrNull(ascension) ?: return null
        val end = rows.getOrNull(ascension + 1) ?: return null
        if (level !in start.level..end.level) return null
        val atStart = level == start.level && ascension > 0
        fun interpolate(
            after: Double?,
            before: Double?,
        ): Double? {
            val startValue = after ?: return null
            val endValue = before ?: return null
            if (atStart) return startValue
            val distance = (end.level - start.level).takeIf { it > 0 } ?: return null
            return startValue + (endValue - startValue) * (level - start.level).toDouble() / distance
        }
        val stats = linkedMapOf(
            "hp" to (interpolate(start.hpAfterAscension, end.hpBeforeAscension) ?: return null),
            "atk" to (interpolate(start.attackAfterAscension, end.attackBeforeAscension) ?: return null),
            "def" to (interpolate(start.defenseAfterAscension, end.defenseBeforeAscension) ?: return null),
            "critRate_" to 5.0,
            "critDMG_" to 50.0,
            "enerRech_" to 100.0,
            "eleMas" to 0.0,
        )
        ascensionStat?.optimizerKey?.let { stat ->
            val bonus = interpolate(
                start.secondaryStatAfterAscension,
                end.secondaryStatBeforeAscension,
            ) ?: 0.0
            stats[stat] = stats.getOrDefault(stat, 0.0) + bonus
        }
        return stats.takeIf { it.values.all(Double::isFinite) }
    }
}
