package de.tyro.genshinapp.service.artifact

import de.tyro.genshinapp.service.catalog.CharacterStatCatalog
import de.tyro.genshinapp.service.catalog.WeaponDataService
import de.tyro.genshinapp.service.catalog.WeaponDefinition
import de.tyro.genshinapp.model.FightProperty
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerWeapon
import org.springframework.stereotype.Service
import kotlin.math.abs

@Service
class OptimizerCombatStatService(
    private val characterStatCatalog: CharacterStatCatalog,
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
        val resolved = characterStatCatalog.resolve(character.key, character.level, character.ascension)
        // Base HP/ATK/DEF are not flat bonuses. In particular ATK must not be added twice.
        val characterStats = resolved?.filterKeys { it !in setOf("hp", "atk", "def") } ?: linkedMapOf(
            "critRate_" to BASE_CRIT_RATE,
            "critDMG_" to BASE_CRIT_DAMAGE,
            "enerRech_" to BASE_ENERGY_RECHARGE,
            "eleMas" to 0.0,
        )
        val characterBaseAttack = resolved?.get("atk")

        val weaponStats = linkedMapOf<String, Double>()
        var weaponBaseAttack: Double? = null
        weapon?.let { equipped ->
            weaponDataService.find(equipped.key)?.let { definition ->
                weaponBaseAttack = resolveWeaponBaseAttack(definition, equipped)
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
            characterBaseAttack = characterBaseAttack,
            weaponBaseAttack = weaponBaseAttack,
            combinedBaseAttack = when {
                characterBaseAttack == null -> null
                weapon == null -> characterBaseAttack
                weaponBaseAttack == null -> null
                else -> characterBaseAttack + weaponBaseAttack!!
            },
        )
    }

    private fun resolveWeaponBaseAttack(
        definition: WeaponDefinition,
        weapon: PlayerWeapon,
    ): Double? {
        definition.hoyolabAscension.firstOrNull { it.level == weapon.level }?.let { progression ->
            val ascensionAtBoundary = WEAPON_ASCENSION_BY_LEVEL[weapon.level]
            return if (ascensionAtBoundary != null && weapon.ascension >= ascensionAtBoundary) {
                progression.attackAfterAscension ?: progression.attackBeforeAscension
            } else {
                progression.attackBeforeAscension ?: progression.attackAfterAscension
            }
        }

        val levelOneAttack = definition.baseAttack ?: return null
        if (weapon.level == 1) return levelOneAttack
        if (weapon.level != 90) return null
        return WEAPON_LEVEL_90_ARCHETYPES
            .filter { archetype -> archetype.rarity == definition.rarity }
            .minByOrNull { archetype -> abs(archetype.levelOneAttack - levelOneAttack) }
            ?.takeIf { archetype -> abs(archetype.levelOneAttack - levelOneAttack) < 0.1 }
            ?.levelNinetyAttack
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
        private val WEAPON_ASCENSION_BY_LEVEL = mapOf(
            20 to 1,
            40 to 2,
            50 to 3,
            60 to 4,
            70 to 5,
            80 to 6,
        )
        private val WEAPON_LEVEL_90_ARCHETYPES = listOf(
            WeaponAttackArchetype(5, 44.3358, 541.83),
            WeaponAttackArchetype(5, 45.9364, 608.07),
            WeaponAttackArchetype(5, 47.537, 674.33),
        )

        fun combatStatKey(type: FightProperty?): String? = type?.optimizerKey
    }

    private data class WeaponAttackArchetype(
        val rarity: Int,
        val levelOneAttack: Double,
        val levelNinetyAttack: Double,
    )
}

data class OptimizerBaseStats(
    val characterStats: Map<String, Double>,
    val weaponStats: Map<String, Double>,
    val bonusStats: Map<String, Double>,
    val characterBaseAttack: Double? = null,
    val weaponBaseAttack: Double? = null,
    val combinedBaseAttack: Double? = null,
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

    fun totalAttack(totalValues: Map<String, Double>): Double? = combinedBaseAttack?.let { base ->
        val attackPercent = totalValues.getOrDefault("atk_", 0.0)
        val flatAttack = totalValues.getOrDefault("atk", 0.0)
        base * (1.0 + attackPercent / 100.0) + flatAttack
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
