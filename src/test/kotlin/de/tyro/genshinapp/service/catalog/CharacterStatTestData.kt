package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterStatProfile
import de.tyro.genshinapp.model.FightProperty
import de.tyro.genshinapp.model.FightProperty.*
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

object CharacterStatTestData {
    val profiles = mapOf(
        "nefer" to profile(FIGHT_PROP_CRITICAL_HURT, 344.4168692, 12703.9082338, 38.4),
        "lauma" to profile(FIGHT_PROP_ELEMENT_MASTERY, 300.0, 12000.0, 115.2),
        "nahida" to profile(FIGHT_PROP_ELEMENT_MASTERY, 300.0, 10000.0, 115.2),
        "columbina" to profile(FIGHT_PROP_CRITICAL, 300.0, 10000.0, 19.2),
    )

    fun catalog(): CharacterStatCatalog {
        val store = mock(CharacterCatalogStore::class.java)
        `when`(store.findCharacter(anyString())).thenAnswer { call ->
            profiles[call.getArgument<String>(0)]?.let { profile ->
                mock(CharacterDefinition::class.java).also { `when`(it.stats).thenReturn(profile) }
            }
        }
        return CharacterStatCatalog(store)
    }

    private fun profile(stat: FightProperty, finalAttack: Double, finalHp: Double, finalBonus: Double): CharacterStatProfile {
        val base = mapOf(FIGHT_PROP_BASE_HP to 800.0, FIGHT_PROP_BASE_ATTACK to 20.0, FIGHT_PROP_BASE_DEFENSE to 70.0)
        val curves = CharacterGrowthCurves.growingStats.associateWith {
            if (it == FIGHT_PROP_BASE_ATTACK) "GROW_CURVE_ATTACK_S5" else "GROW_CURVE_HP_S5"
        }
        val finalGrowth = mapOf(FIGHT_PROP_BASE_HP to finalHp - 810.0, FIGHT_PROP_BASE_ATTACK to finalAttack - 21.0,
            FIGHT_PROP_BASE_DEFENSE to 630.0)
        val growth = listOf(20, 40, 50, 60, 70, 80, 90).associateWith { level ->
            finalGrowth.mapValues { (key, total) ->
                total * (CharacterGrowthCurves.multiplier(curves.getValue(key), level)!! - 1) /
                    (CharacterGrowthCurves.multiplier(curves.getValue(key), 90)!! - 1)
            }
        }
        val bonuses = (1..6).associateWith { phase ->
            mapOf(
                FIGHT_PROP_BASE_HP to if (phase == 6) 10.0 else 0.0,
                FIGHT_PROP_BASE_ATTACK to if (phase == 6) 1.0 else 0.0,
                FIGHT_PROP_BASE_DEFENSE to 0.0,
                stat to finalBonus * listOf(0.0, 0.0, 0.25, 0.5, 0.5, 0.75, 1.0)[phase],
            )
        }
        return CharacterStatProfile(base, curves, growth, bonuses)
    }
}
