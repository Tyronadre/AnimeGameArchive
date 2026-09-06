package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.model.CharacterBaseValues
import de.tyro.genshinapp.model.CharacterHoyolabAscension
import de.tyro.genshinapp.model.CharacterStatProfile
import de.tyro.genshinapp.model.FightProperty
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

object CharacterStatTestData {
    val profiles = listOf(
        profile("nefer", FightProperty.FIGHT_PROP_CRITICAL_HURT, 344.4168692, 12703.9082338, 38.4),
        profile("lauma", FightProperty.FIGHT_PROP_ELEMENT_MASTERY, 300.0, 12000.0, 115.2),
        profile("nahida", FightProperty.FIGHT_PROP_ELEMENT_MASTERY, 300.0, 10000.0, 115.2),
        profile("columbina", FightProperty.FIGHT_PROP_CRITICAL, 300.0, 10000.0, 19.2),
    ).associateBy(CharacterStatProfile::key)

    fun mockCatalog(): CharacterStatCatalog = mock(CharacterStatCatalog::class.java).also { catalog ->
        `when`(catalog.find(anyString())).thenAnswer { call -> profiles[CharacterStatCatalog.sourceKey(call.getArgument(0))] }
        `when`(catalog.resolve(anyString(), anyInt(), anyInt())).thenAnswer { call ->
            profiles[CharacterStatCatalog.sourceKey(call.getArgument(0))]?.resolve(call.getArgument(1), call.getArgument(2))
        }
    }

    private fun profile(
        key: String,
        stat: FightProperty,
        finalAttack: Double,
        finalHp: Double,
        finalBonus: Double,
    ): CharacterStatProfile {
        val levels = listOf(1, 20, 40, 50, 60, 70, 80, 90)
        val rows = levels.mapIndexed { index, level ->
            val progress = index.toDouble() / levels.lastIndex
            val attack = 20.0 + (finalAttack - 20.0) * progress
            val hp = 800.0 + (finalHp - 800.0) * progress
            val bonus = when {
                level < 40 -> 0.0
                level < 50 -> finalBonus / 4
                level < 70 -> finalBonus / 2
                level < 80 -> finalBonus * 3 / 4
                else -> finalBonus
            }
            CharacterHoyolabAscension(
                level = level,
                hpBeforeAscension = hp,
                hpAfterAscension = hp + if (level == 80) 10 else 0,
                attackBeforeAscension = attack,
                attackAfterAscension = attack + if (level == 80) 1 else 0,
                defenseBeforeAscension = 700.0 * progress.coerceAtLeast(0.1),
                defenseAfterAscension = 700.0 * progress.coerceAtLeast(0.1),
                secondaryStatBeforeAscension = if (level == 80) finalBonus * 3 / 4 else bonus,
                secondaryStatAfterAscension = bonus,
            )
        }
        return CharacterStatProfile(
            key,
            CharacterBaseValues(800.0, 20.0, 70.0, 5.0, 50.0, 100.0, 0.0),
            null,
            null,
            null,
            stat,
            emptyList(),
            "test",
            "https://wiki.hoyolab.com/",
            rows,
        )
    }
}
