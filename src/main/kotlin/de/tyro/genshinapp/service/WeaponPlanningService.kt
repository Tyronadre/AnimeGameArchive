package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.MaterialRequirement
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.PlayerWeapon
import kotlin.math.ceil
import org.springframework.stereotype.Service

@Service
class WeaponPlanningService(
    private val weaponDataService: WeaponDataService,
    private val materialCatalogService: MaterialCatalogService,
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

        val enhancement = calculateEnhancement(
            definition.rarity,
            weapon.level,
            targetLevel,
        )
        if (enhancement.experience > 0) {
            val mysticOre = enhancement.mysticEnhancementOre
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
                imageUrl = materialCatalogService.materialImageUrl(identity.first),
            )
        }
    }

    fun calculateEnhancement(
        rarity: Int,
        currentLevel: Int,
        targetLevel: Int,
    ): WeaponEnhancementRequirement {
        if (targetLevel <= currentLevel) return WeaponEnhancementRequirement.EMPTY
        val perLevel = EXPERIENCE_TO_NEXT_LEVEL_BY_RARITY[rarity.coerceIn(1, 5)]
            ?: return WeaponEnhancementRequirement.EMPTY
        val from = currentLevel.coerceIn(1, perLevel.size)
        val to = targetLevel.coerceIn(from, perLevel.size)
        // GOOD exposes the level but not the partially filled EXP bar. Starting at zero EXP in
        // the imported level is deterministic and deliberately gives a conservative requirement.
        val experience = (from until to).sumOf { level -> perLevel[level - 1] }
        return WeaponEnhancementRequirement(
            experience = experience,
            mysticEnhancementOre = ceil(experience.toDouble() / MYSTIC_ORE_EXPERIENCE).toLong(),
        )
    }

    companion object {
        private const val MYSTIC_ENHANCEMENT_ORE_ID = 104013
        private const val MYSTIC_ENHANCEMENT_ORE_NAME = "Mystic Enhancement Ore"
        private const val MYSTIC_ORE_EXPERIENCE = 10_000
        private const val MORA_ID = 202
        private const val MORA_NAME = "Mora"
        private const val MORA_PER_MYSTIC_ORE = 1_000L
        private val LEVEL_CAPS = listOf(20, 40, 50, 60, 70, 80, 90)

        private val EXPERIENCE_TO_NEXT_LEVEL_BY_RARITY = mapOf(
            1 to longArrayOf(
                125, 200, 275, 350, 475, 575, 700, 850, 1_000, 1_150, 1_300, 1_475,
                1_650, 1_850, 2_050, 2_250, 2_450, 2_675, 2_925, 3_150, 3_575, 3_825,
                4_100, 4_400, 4_700, 5_000, 5_300, 5_600, 5_925, 6_275, 6_600, 6_950,
                7_325, 7_675, 8_050, 8_425, 8_825, 9_225, 9_625, 10_025, 10_975,
                11_425, 11_875, 12_350, 12_825, 13_300, 13_775, 14_275, 14_800,
                15_300, 16_625, 17_175, 17_725, 18_300, 18_875, 19_475, 20_075,
                20_675, 21_300, 21_925, 23_675, 24_350, 25_025, 25_700, 26_400,
                27_125, 27_825, 28_550, 29_275, 30_025, 32_300, 33_100, 33_900,
                34_700, 35_525, 36_350, 37_200, 38_050, 38_900, 39_775, 46_950,
                52_775, 59_275, 66_600, 74_800, 83_975, 94_275, 105_800, 118_725,
                133_200,
            ),
            2 to longArrayOf(
                175, 275, 400, 550, 700, 875, 1_050, 1_250, 1_475, 1_700, 1_950, 2_225,
                2_475, 2_775, 3_050, 3_375, 3_700, 4_025, 4_375, 4_725, 5_350, 5_750,
                6_175, 6_600, 7_025, 7_475, 7_950, 8_425, 8_900, 9_400, 9_900,
                10_450, 10_975, 11_525, 12_075, 12_650, 13_225, 13_825, 14_425,
                15_050, 16_450, 17_125, 17_825, 18_525, 19_225, 19_950, 20_675,
                21_425, 22_175, 22_950, 24_925, 25_750, 26_600, 27_450, 28_325,
                29_225, 30_100, 31_025, 31_950, 32_875, 35_500, 36_500, 37_525,
                38_575, 39_600, 40_675, 41_750, 42_825, 43_900, 45_025, 48_450,
                49_650, 50_850, 52_075, 53_300, 54_550, 55_800, 57_075, 58_350,
                59_650, 70_425, 79_150, 88_925, 99_900, 112_175, 125_975, 141_425,
                158_725, 178_100, 199_800,
            ),
            3 to longArrayOf(
                275, 425, 600, 800, 1_025, 1_275, 1_550, 1_850, 2_175, 2_500, 2_875,
                3_250, 3_650, 4_050, 4_500, 4_950, 5_400, 5_900, 6_425, 6_925,
                7_850, 8_425, 9_050, 9_675, 10_325, 10_975, 11_650, 12_350,
                13_050, 13_800, 14_525, 15_300, 16_100, 16_900, 17_700, 18_550,
                19_400, 20_275, 21_175, 22_050, 24_150, 25_125, 26_125, 27_150,
                28_200, 29_250, 30_325, 31_425, 32_550, 33_650, 36_550, 37_775,
                39_000, 40_275, 41_550, 42_850, 44_150, 45_500, 46_850, 48_225,
                52_075, 53_550, 55_050, 56_550, 58_100, 59_650, 61_225, 62_800,
                64_400, 66_025, 71_075, 72_825, 74_575, 76_350, 78_150, 80_000,
                81_850, 83_700, 85_575, 87_500, 103_275, 116_075, 130_425, 146_500,
                164_550, 184_775, 207_400, 232_775, 261_200, 293_050,
            ),
            4 to longArrayOf(
                400, 625, 900, 1_200, 1_550, 1_950, 2_350, 2_800, 3_300, 3_800,
                4_350, 4_925, 5_525, 6_150, 6_800, 7_500, 8_200, 8_950, 9_725,
                10_500, 11_900, 12_775, 13_700, 14_650, 15_625, 16_625, 17_650,
                18_700, 19_775, 20_900, 22_025, 23_200, 24_375, 25_600, 26_825,
                28_100, 29_400, 30_725, 32_075, 33_425, 36_575, 38_075, 39_600,
                41_150, 42_725, 44_325, 45_950, 47_600, 49_300, 51_000, 55_375,
                57_225, 59_100, 61_025, 62_950, 64_925, 66_900, 68_925, 70_975,
                73_050, 78_900, 81_125, 83_400, 85_700, 88_025, 90_375, 92_750,
                95_150, 97_575, 100_050, 107_675, 110_325, 113_000, 115_700,
                118_425, 121_200, 124_000, 126_825, 129_675, 132_575, 156_475,
                175_875, 197_600, 221_975, 249_300, 279_950, 314_250, 352_700,
                395_775, 444_025,
            ),
            5 to longArrayOf(
                600, 950, 1_350, 1_800, 2_325, 2_925, 3_525, 4_200, 4_950, 5_700,
                6_525, 7_400, 8_300, 9_225, 10_200, 11_250, 12_300, 13_425,
                14_600, 15_750, 17_850, 19_175, 20_550, 21_975, 23_450, 24_950,
                26_475, 28_050, 29_675, 31_350, 33_050, 34_800, 36_575, 38_400,
                40_250, 42_150, 44_100, 46_100, 48_125, 50_150, 54_875, 57_125,
                59_400, 61_725, 64_100, 66_500, 68_925, 71_400, 73_950, 76_500,
                83_075, 85_850, 88_650, 91_550, 94_425, 97_400, 100_350,
                103_400, 106_475, 109_575, 118_350, 121_700, 125_100, 128_550,
                132_050, 135_575, 139_125, 142_725, 146_375, 150_075, 161_525,
                165_500, 169_500, 173_550, 177_650, 181_800, 186_000, 190_250,
                194_525, 198_875, 234_725, 263_825, 296_400, 332_975, 373_950,
                419_925, 471_375, 529_050, 593_675, 666_050,
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

        fun maximumAscension(rarity: Int): Int = if (rarity >= 3) 6 else 4

    }
}

data class WeaponEnhancementRequirement(
    val experience: Long,
    val mysticEnhancementOre: Long,
) {
    companion object {
        val EMPTY = WeaponEnhancementRequirement(0, 0)
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
