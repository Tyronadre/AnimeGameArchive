package de.tyro.genshinapp.service.importer

import com.fasterxml.jackson.databind.JsonNode
import de.tyro.genshinapp.model.CharacterStatProfile
import de.tyro.genshinapp.model.FightProperty
import de.tyro.genshinapp.service.catalog.CharacterGrowthCurves

/** Converts the display table once, at import time, into independent base, growth and ascension values. */
class HoyolabCharacterStatsParser(private val parser: HoyolabPageParser) {
    fun parse(rows: List<JsonNode>, ascensionStat: FightProperty?): CharacterStatProfile? {
        val parsed = rows.mapNotNull { parseRow(it, ascensionStat) }
        val levels = CharacterGrowthCurves.phaseLevels.map { it.first } + 90
        val ordered = levels.map { level -> parsed.singleOrNull { it.level == level } ?: return null }
        val base = ordered.first().after
        val growing = CharacterGrowthCurves.growingStats
        if (growing.any { (base[it] ?: 0.0) <= 0.0 }) return null
        if (ascensionStat != null && ascensionStat !in base) return null
        val secondaryStats = base.keys - growing.toSet()

        val growth = linkedMapOf<Int, Map<FightProperty, Double>>()
        val bonuses = linkedMapOf<Int, Map<FightProperty, Double>>()
        val cumulative = growing.associateWith { 0.0 }.toMutableMap()
        for (index in 1..ordered.lastIndex) {
            val row = ordered[index]
            val levelValues = growing.associateWith { stat ->
                val before = row.before[stat] ?: return null
                before - base.getValue(stat) - cumulative.getValue(stat)
            }
            if (levelValues.values.any { it < 0 || !it.isFinite() }) return null
            growth[row.level] = levelValues
            if (index == ordered.lastIndex) continue // Level 90 has only a before-ascension column.
            growing.forEach { stat ->
                val increase = (row.after[stat] ?: return null) - row.before.getValue(stat)
                if (increase < 0) return null
                cumulative[stat] = cumulative.getValue(stat) + increase
            }
            val phase = cumulative.toMutableMap()
            secondaryStats.forEach { stat ->
                val after = row.after[stat] ?: return null
                phase[stat] = after - base.getValue(stat)
            }
            bonuses[index] = phase
        }
        val curves = CharacterGrowthCurves.identify(growth) ?: return null
        return CharacterStatProfile(base.filterValues { it != 0.0 }, curves, growth, bonuses)
    }

    private fun parseRow(row: JsonNode, ascensionStat: FightProperty?): Row? {
        val level = Regex("\\d+").find(row.path("key").asText())?.value?.toIntOrNull() ?: return null
        val combat = row.path("combatList").takeIf(JsonNode::isArray)?.toList() ?: return null
        val statRows = combat.filter { label(it).isNotBlank() }
        val before = linkedMapOf<FightProperty, Double>()
        val after = linkedMapOf<FightProperty, Double>()
        statRows.forEach { statRow ->
            val label = label(statRow)
            val stat = FightProperty.fromExternal(label)
                ?: FightProperty.fromExternal(label.removeSuffix(" bonus"))
                ?: ascensionStat?.takeIf { statRow === statRows.last() }
                ?: return@forEach
            val values = statRow.path("values")
            parser.number(parser.plainText(values.path(0).asText()))?.takeIf(Double::isFinite)?.let { before[stat] = it }
            parser.number(parser.plainText(values.path(1).asText()))?.takeIf(Double::isFinite)?.let { after[stat] = it }
        }
        return Row(level, before, after)
    }

    private fun label(row: JsonNode): String = parser.plainText(row.path("key").asText()).orEmpty()
        .replace(Regex("[\\s\\p{Zs}]+"), " ").trim().lowercase(java.util.Locale.ROOT)

    private data class Row(
        val level: Int,
        val before: Map<FightProperty, Double>,
        val after: Map<FightProperty, Double>,
    )
}
