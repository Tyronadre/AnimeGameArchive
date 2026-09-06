package de.tyro.genshinapp.service.catalog

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.FightProperty
import org.springframework.core.io.ClassPathResource

/** Pinned game curves, available offline. DEF uses the HP curve. */
object CharacterGrowthCurves {
    val growingStats = listOf(
        FightProperty.FIGHT_PROP_BASE_HP,
        FightProperty.FIGHT_PROP_BASE_ATTACK,
        FightProperty.FIGHT_PROP_BASE_DEFENSE,
    )
    val phaseLevels = listOf(1..20, 20..40, 40..50, 50..60, 60..70, 70..80, 80..90)

    private val curves: Map<String, Map<Int, Double>> =
        ClassPathResource("data/character-growth-curves.json").inputStream.use { input ->
            jacksonObjectMapper().readTree(input).properties().associate { (key, values) ->
                key to (1..90).associateWith { level -> values[level].asDouble() }
            }
        }

    fun multiplier(key: String, level: Int): Double? = curves[key]?.get(level)

    /** Fit observed level-only growth, not rarity (the Traveler is a five-star using S4 curves). */
    fun identify(growth: Map<Int, Map<FightProperty, Double>>): Map<FightProperty, String>? {
        val observations = growth.filterKeys { it > 1 }
        if (observations.size < 2) return null
        // Compare the whole character: rounding of a small ATK value alone can suggest the wrong family.
        val family = listOf("S4", "S5").minByOrNull { family ->
            growingStats.sumOf { stat ->
                val curve = curves.getValue(curveKey(stat, family))
                val coefficient = observations.entries.sumOf { (level, values) ->
                    (curve.getValue(level) - 1) * values.getValue(stat)
                } / observations.keys.sumOf { level -> (curve.getValue(level) - 1).let { it * it } }
                observations.entries.sumOf { (level, values) ->
                    (values.getValue(stat) - coefficient * (curve.getValue(level) - 1)).let { it * it }
                }
            }
        } ?: return null
        return growingStats.associateWith { curveKey(it, family) }
    }

    private fun curveKey(stat: FightProperty, family: String): String =
        if (stat == FightProperty.FIGHT_PROP_BASE_ATTACK) "GROW_CURVE_ATTACK_$family" else "GROW_CURVE_HP_$family"
}
