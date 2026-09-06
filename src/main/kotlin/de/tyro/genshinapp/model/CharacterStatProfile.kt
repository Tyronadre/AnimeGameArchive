package de.tyro.genshinapp.model

/** Imported values use percentage points. Missing base stats are zero, apart from universal defaults. */
data class CharacterStatProfile(
    val baseStats: Map<FightProperty, Double>,
    val growthCurves: Map<FightProperty, String>,
    /** Cumulative level growth, excluding both the base value and all ascension bonuses. */
    val levelGrowth: Map<Int, Map<FightProperty, Double>>,
    /** Cumulative bonuses for each unlocked ascension phase, excluding level-one values. */
    val ascensionBonuses: Map<Int, Map<FightProperty, Double>>,
)
