package de.tyro.genshinapp.util

import de.tyro.genshinapp.entity.GameCharacterStats

object CharacterUtil {
    fun allowedTalentLevel(level: Int): Boolean {
        return level > 0 && level <= 10
    }

    fun allowedCharacterLevel(level: Int): Boolean {
        return (level > 0 && level <= 90) || level == 95 || level == 100
    }

    fun allowedConstellationLevel(level: Int): Boolean {
        return level > 0 && level <= 6
    }

    fun allowedAscensionLevel(gameCharacterStats: GameCharacterStats, level: Int): Boolean {
        return isAscensionLevel(gameCharacterStats.characterLevel)
    }

    fun isAscensionLevel(level: Int): Boolean {
        return when(level) {
            20,40,50,60,70,80,90,95 -> true
            else -> false
        }
    }

}
