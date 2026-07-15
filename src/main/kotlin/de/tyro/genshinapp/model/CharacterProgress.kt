package de.tyro.genshinapp.model

data class CharacterProgress(
    val owned: Boolean = false,
    val level: Int = 1,
    val ascension: Int = 0,
    val constellation: Int = 0,
    val normalTalent: Int = 1,
    val skillTalent: Int = 1,
    val burstTalent: Int = 1,
    val targetLevel: Int = 80,
    val targetAscension: Int = 6,
    val targetNormalTalent: Int = 9,
    val targetSkillTalent: Int = 9,
    val targetBurstTalent: Int = 9,
) {
    init {
        require(level in 1..90)
        require(ascension in minimumAscensionFor(level)..6)
        require(constellation in 0..6)
        require(normalTalent in 1..10 && skillTalent in 1..10 && burstTalent in 1..10)
        require(targetLevel in level..90)
        require(targetAscension in ascension..6)
        require(targetAscension >= minimumAscensionFor(targetLevel))
        require(targetNormalTalent in normalTalent..10)
        require(targetSkillTalent in skillTalent..10)
        require(targetBurstTalent in burstTalent..10)
    }

    companion object {
        fun minimumAscensionFor(level: Int): Int = when {
            level > 80 -> 6
            level > 70 -> 5
            level > 60 -> 4
            level > 50 -> 3
            level > 40 -> 2
            level > 20 -> 1
            else -> 0
        }
    }
}

class CharacterProgressForm {
    var owned: Boolean = false
    var ownershipExplicit: Boolean = false
    var level: Int = 1
    var ascension: Int = 0
    var constellation: Int = 0
    var normalTalent: Int = 1
    var skillTalent: Int = 1
    var burstTalent: Int = 1
    var targetLevel: Int = 80
    var targetAscension: Int = 6
    var targetNormalTalent: Int = 9
    var targetSkillTalent: Int = 9
    var targetBurstTalent: Int = 9

    fun apply(state: PlayerCharacterState) {
        owned = true
        level = state.level
        ascension = state.ascension
        constellation = state.constellation
        normalTalent = state.normalTalent
        skillTalent = state.skillTalent
        burstTalent = state.burstTalent
        targetLevel = maxOf(DEFAULT_TARGET_LEVEL, state.level)
        targetAscension = maxOf(DEFAULT_TARGET_ASCENSION, state.ascension)
        targetNormalTalent = maxOf(DEFAULT_TARGET_TALENT, state.normalTalent)
        targetSkillTalent = maxOf(DEFAULT_TARGET_TALENT, state.skillTalent)
        targetBurstTalent = maxOf(DEFAULT_TARGET_TALENT, state.burstTalent)
    }

    fun applyShared(state: PlayerCharacterState) {
        owned = true
        level = state.level
        ascension = state.ascension
        targetLevel = maxOf(DEFAULT_TARGET_LEVEL, state.level)
        targetAscension = maxOf(DEFAULT_TARGET_ASCENSION, state.ascension)
    }

    fun normalized(): CharacterProgress {
        val effectiveOwned = owned || (!ownershipExplicit && hasCurrentProgress())
        val safeLevel = if (effectiveOwned) level.coerceIn(1, 90) else 1
        val safeAscension = if (effectiveOwned) {
            ascension.coerceIn(CharacterProgress.minimumAscensionFor(safeLevel), 6)
        } else {
            0
        }
        val safeConstellation = if (effectiveOwned) constellation.coerceIn(0, 6) else 0
        val safeNormal = if (effectiveOwned) normalTalent.coerceIn(1, 10) else 1
        val safeSkill = if (effectiveOwned) skillTalent.coerceIn(1, 10) else 1
        val safeBurst = if (effectiveOwned) burstTalent.coerceIn(1, 10) else 1
        val safeTargetLevel = targetLevel.coerceIn(safeLevel, 90)
        val minimumTargetAscension = maxOf(
            safeAscension,
            CharacterProgress.minimumAscensionFor(safeTargetLevel),
        )

        return CharacterProgress(
            owned = effectiveOwned,
            level = safeLevel,
            ascension = safeAscension,
            constellation = safeConstellation,
            normalTalent = safeNormal,
            skillTalent = safeSkill,
            burstTalent = safeBurst,
            targetLevel = safeTargetLevel,
            targetAscension = targetAscension.coerceIn(minimumTargetAscension, 6),
            targetNormalTalent = targetNormalTalent.coerceIn(safeNormal, 10),
            targetSkillTalent = targetSkillTalent.coerceIn(safeSkill, 10),
            targetBurstTalent = targetBurstTalent.coerceIn(safeBurst, 10),
        )
    }

    private fun hasCurrentProgress(): Boolean =
        level > 1 || ascension > 0 || constellation > 0 ||
            normalTalent > 1 || skillTalent > 1 || burstTalent > 1

    companion object {
        const val DEFAULT_TARGET_LEVEL = 80
        const val DEFAULT_TARGET_ASCENSION = 6
        const val DEFAULT_TARGET_TALENT = 9
    }
}
