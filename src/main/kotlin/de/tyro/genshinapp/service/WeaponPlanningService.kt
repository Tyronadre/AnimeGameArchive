package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.MaterialRequirement
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.PlayerWeapon
import kotlin.math.ceil
import org.springframework.stereotype.Service

@Service
class WeaponPlanningService(
    private val weaponDataService: WeaponDataService,
    private val catalogService: CharacterCatalogService,
    private val planningService: PlayerPlanningService,
) {
    fun createPlan(
        weapon: PlayerWeapon,
        requestedTargetLevel: Int,
        snapshot: PlayerSnapshot,
    ): WeaponUpgradePlan? {
        val definition = weaponDataService.find(weapon.key) ?: return null
        val targetLevel = normalizeTargetLevel(
            weapon.level,
            requestedTargetLevel,
            maxLevel(definition.rarity),
        )
        val requirements = calculateRequirements(weapon, definition, targetLevel)
        return WeaponUpgradePlan(
            weapon = weapon,
            definition = definition,
            targetLevel = targetLevel,
            materials = planningService.calculateBalances(requirements, snapshot),
        )
    }

    fun calculateRequirements(
        weapon: PlayerWeapon,
        definition: WeaponDefinition,
        targetLevel: Int,
    ): List<MaterialRequirement> {
        if (targetLevel <= weapon.level) return emptyList()
        val targetAscension = minimumAscensionFor(targetLevel)
        val totals = linkedMapOf<Pair<Int, String>, Long>()
        ((weapon.ascension + 1)..targetAscension)
            .flatMap { definition.ascensionCosts[it].orEmpty() }
            .forEach { cost ->
                val key = cost.id to cost.name
                totals[key] = totals.getOrDefault(key, 0L) + cost.count
            }

        val experience = experienceBetween(
            definition.rarity,
            weapon.level,
            targetLevel,
        )
        if (experience > 0) {
            val mysticOre = ceil(experience.toDouble() / MYSTIC_ORE_EXPERIENCE).toLong()
            totals[MYSTIC_ENHANCEMENT_ORE_ID to MYSTIC_ENHANCEMENT_ORE_NAME] =
                mysticOre
            val levelingMora = mysticOre * MORA_PER_MYSTIC_ORE
            val moraKey = MORA_ID to MORA_NAME
            totals[moraKey] = totals.getOrDefault(moraKey, 0L) + levelingMora
        }

        return totals.map { (identity, amount) ->
            MaterialRequirement(
                id = identity.first,
                name = identity.second,
                amount = amount,
                imageUrl = catalogService.materialImageUrl(identity.first),
            )
        }
    }

    private fun experienceBetween(
        rarity: Int,
        currentLevel: Int,
        targetLevel: Int,
    ): Long {
        val cumulative = CUMULATIVE_EXPERIENCE_BY_RARITY[
            rarity.coerceIn(1, 5)
        ] ?: return 0L
        val from = cumulative[currentLevel] ?: cumulative.entries
            .filter { it.key <= currentLevel }
            .maxByOrNull(Map.Entry<Int, Long>::key)
            ?.value
            ?: 0L
        val to = cumulative[targetLevel] ?: return 0L
        return (to - from).coerceAtLeast(0L)
    }

    companion object {
        private const val MYSTIC_ENHANCEMENT_ORE_ID = 104013
        private const val MYSTIC_ENHANCEMENT_ORE_NAME = "Mystic Enhancement Ore"
        private const val MYSTIC_ORE_EXPERIENCE = 10_000
        private const val MORA_ID = 202
        private const val MORA_NAME = "Mora"
        private const val MORA_PER_MYSTIC_ORE = 1_000L
        private val LEVEL_CAPS = listOf(20, 40, 50, 60, 70, 80, 90)

        private val CUMULATIVE_EXPERIENCE_BY_RARITY = mapOf(
            1 to cumulative(24_325, 124_550, 125_625, 185_525, 259_850),
            2 to cumulative(36_400, 186_825, 188_425, 278_300, 389_725),
            3 to cumulative(
                53_475,
                274_000,
                276_350,
                408_150,
                571_625,
                770_125,
                1_634_475,
            ),
            4 to cumulative(
                81_000,
                415_125,
                418_725,
                618_400,
                866_050,
                1_166_875,
                2_476_475,
            ),
            5 to cumulative(
                121_550,
                622_800,
                628_150,
                927_675,
                1_299_125,
                1_750_375,
                3_714_775,
            ),
        )

        fun validTargetLevels(
            currentLevel: Int,
            maxLevel: Int = 90,
        ): List<Int> = (
            listOf(currentLevel) + LEVEL_CAPS.filter {
                it > currentLevel && it <= maxLevel
            }
            ).distinct()

        fun normalizeTargetLevel(
            currentLevel: Int,
            requestedTargetLevel: Int,
            maxLevel: Int = 90,
        ): Int = validTargetLevels(currentLevel, maxLevel)
            .firstOrNull { it >= requestedTargetLevel }
            ?: validTargetLevels(currentLevel, maxLevel).last()

        fun minimumAscensionFor(level: Int): Int = when {
            level > 80 -> 6
            level > 70 -> 5
            level > 60 -> 4
            level > 50 -> 3
            level > 40 -> 2
            level > 20 -> 1
            else -> 0
        }

        fun maxLevel(rarity: Int): Int = if (rarity >= 3) 90 else 70

        private fun cumulative(vararg ranges: Int): Map<Int, Long> {
            var total = 0L
            val result = linkedMapOf(1 to 0L)
            ranges.forEachIndexed { index, range ->
                total += range
                result[LEVEL_CAPS[index]] = total
            }
            return result
        }
    }
}

data class WeaponUpgradePlan(
    val weapon: PlayerWeapon,
    val definition: WeaponDefinition,
    val targetLevel: Int,
    val materials: List<de.tyro.genshinapp.model.InventoryMaterialBalance>,
) {
    val missingMaterials: List<de.tyro.genshinapp.model.InventoryMaterialBalance>
        get() = materials.filter { it.missing > 0 }

    val complete: Boolean
        get() = weapon.level >= targetLevel && missingMaterials.isEmpty()
}
