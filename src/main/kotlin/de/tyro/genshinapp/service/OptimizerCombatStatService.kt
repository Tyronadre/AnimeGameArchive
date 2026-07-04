package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerWeapon
import org.springframework.stereotype.Service

@Service
class OptimizerCombatStatService(
    private val characterCatalogService: CharacterCatalogService,
    private val weaponDataService: WeaponDataService,
) {
    fun resolve(
        character: PlayerCharacterState,
        weapon: PlayerWeapon?,
        additionalCritRate: Double,
    ): OptimizerBaseStats = resolve(
        character,
        weapon,
        if (additionalCritRate != 0.0) {
            mapOf("critRate_" to additionalCritRate)
        } else {
            emptyMap()
        },
    )

    fun resolve(
        character: PlayerCharacterState,
        weapon: PlayerWeapon?,
        additionalStats: Map<String, Double>,
    ): OptimizerBaseStats {
        val characterStats = linkedMapOf(
            "critRate_" to BASE_CRIT_RATE,
            "critDMG_" to BASE_CRIT_DAMAGE,
            "enerRech_" to BASE_ENERGY_RECHARGE,
            "eleMas" to 0.0,
        )
        characterCatalogService.findCharacter(character.key)?.let { definition ->
            val statKey = combatStatKey(definition.ascensionStatType)
            val value = characterAscensionStat(
                statKey = statKey,
                rarity = definition.rarity,
                ascension = character.ascension,
            )
            if (statKey != null && value > 0.0) {
                characterStats[statKey] = characterStats.getOrDefault(statKey, 0.0) + value
            }
        }

        val weaponStats = linkedMapOf<String, Double>()
        weapon?.let { equipped ->
            weaponDataService.find(equipped.key)?.let { definition ->
                val statKey = combatStatKey(definition.secondaryStatType)
                val baseValue = definition.baseSecondaryStat
                if (statKey != null && baseValue != null) {
                    weaponStats[statKey] = weaponSecondaryStat(baseValue, equipped.level)
                }
            }
        }

        return OptimizerBaseStats(
            characterStats = characterStats,
            weaponStats = weaponStats,
            bonusStats = additionalStats.filterValues { it != 0.0 },
        )
    }

    private fun characterAscensionStat(
        statKey: String?,
        rarity: Int,
        ascension: Int,
    ): Double {
        if (statKey == null) return 0.0
        val unlockedSteps = ASCENSION_STAT_PHASES.count { ascension >= it }
        if (unlockedSteps == 0) return 0.0
        val fiveStar = rarity >= 5
        val increment = when (statKey) {
            "critRate_" -> if (fiveStar) 4.8 else 4.0
            "critDMG_" -> if (fiveStar) 9.6 else 8.0
            "enerRech_" -> if (fiveStar) 8.0 else 6.67
            "eleMas" -> if (fiveStar) 28.8 else 24.0
            else -> if (fiveStar) 7.2 else 6.0
        }
        return increment * unlockedSteps
    }

    private fun weaponSecondaryStat(baseValue: Double, level: Int): Double {
        val progress = (level.coerceIn(1, 90) - 1) / 89.0
        return baseValue * (1.0 + (WEAPON_LEVEL_90_MULTIPLIER - 1.0) * progress)
    }

    companion object {
        private const val BASE_CRIT_RATE = 5.0
        private const val BASE_CRIT_DAMAGE = 50.0
        private const val BASE_ENERGY_RECHARGE = 100.0
        private const val WEAPON_LEVEL_90_MULTIPLIER = 4.594
        private val ASCENSION_STAT_PHASES = listOf(2, 3, 5, 6)

        fun combatStatKey(type: String?): String? = when (type) {
            "FIGHT_PROP_CRITICAL" -> "critRate_"
            "FIGHT_PROP_CRITICAL_HURT" -> "critDMG_"
            "FIGHT_PROP_CHARGE_EFFICIENCY" -> "enerRech_"
            "FIGHT_PROP_ELEMENT_MASTERY" -> "eleMas"
            "FIGHT_PROP_ATTACK_PERCENT" -> "atk_"
            "FIGHT_PROP_HP_PERCENT" -> "hp_"
            "FIGHT_PROP_DEFENSE_PERCENT" -> "def_"
            "FIGHT_PROP_FIRE_ADD_HURT" -> "pyro_dmg_"
            "FIGHT_PROP_WATER_ADD_HURT" -> "hydro_dmg_"
            "FIGHT_PROP_ELEC_ADD_HURT" -> "electro_dmg_"
            "FIGHT_PROP_ICE_ADD_HURT" -> "cryo_dmg_"
            "FIGHT_PROP_WIND_ADD_HURT" -> "anemo_dmg_"
            "FIGHT_PROP_ROCK_ADD_HURT" -> "geo_dmg_"
            "FIGHT_PROP_GRASS_ADD_HURT" -> "dendro_dmg_"
            "FIGHT_PROP_PHYSICAL_ADD_HURT" -> "physical_dmg_"
            else -> null
        }
    }
}

data class OptimizerBaseStats(
    val characterStats: Map<String, Double>,
    val weaponStats: Map<String, Double>,
    val bonusStats: Map<String, Double>,
) {
    val totals: Map<String, Double>
        get() = buildMap {
            (characterStats.keys + weaponStats.keys + bonusStats.keys).forEach { key ->
                put(
                    key,
                    characterStats.getOrDefault(key, 0.0) +
                        weaponStats.getOrDefault(key, 0.0) +
                        bonusStats.getOrDefault(key, 0.0),
                )
            }
        }

    companion object {
        fun defaults(): OptimizerBaseStats = OptimizerBaseStats(
            characterStats = mapOf(
                "critRate_" to 5.0,
                "critDMG_" to 50.0,
                "enerRech_" to 100.0,
                "eleMas" to 0.0,
            ),
            weaponStats = emptyMap(),
            bonusStats = emptyMap(),
        )
    }
}
