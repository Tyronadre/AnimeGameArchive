package de.tyro.genshinapp.model

import java.time.Instant

data class PlayerSnapshot(
    val formatVersion: Int,
    val source: String?,
    val importedAt: Instant,
    val revision: Long = 0,
    val characters: List<PlayerCharacterState>,
    val inventory: Map<String, Long>,
    val inventoryNames: Map<String, String>,
    val exportedInventoryKeys: Int,
    val artifacts: List<PlayerArtifact>,
    val weapons: List<PlayerWeapon>,
)

data class PlayerCharacterState(
    val key: String,
    val level: Int,
    val constellation: Int,
    val ascension: Int,
    val normalTalent: Int,
    val skillTalent: Int,
    val burstTalent: Int,
)

data class PlayerArtifact(
    val setKey: String,
    val slotKey: String,
    val level: Int,
    val rarity: Int,
    val mainStatKey: String,
    val location: String?,
    val locked: Boolean,
    val substats: List<PlayerArtifactStat>,
    val totalRolls: Int?,
    val astralMark: Boolean,
    val elixirCrafted: Boolean,
) {
    val setName: String
        get() = GoodKeyNormalizer.humanize(setKey)

    val slotName: String
        get() = when (slotKey.lowercase()) {
            "flower" -> "Flower of Life"
            "plume" -> "Plume of Death"
            "sands" -> "Sands of Eon"
            "goblet" -> "Goblet of Eonothem"
            "circlet" -> "Circlet of Logos"
            else -> GoodKeyNormalizer.humanize(slotKey)
        }

    val mainStatName: String
        get() = GoodKeyNormalizer.statName(mainStatKey)

    val imageKey: String
        get() = "${GoodKeyNormalizer.normalize(setKey)}:${slotKey.lowercase()}"
}

data class PlayerArtifactStat(
    val key: String,
    val value: Double,
) {
    val name: String
        get() = GoodKeyNormalizer.statName(key)

    val formattedValue: String
        get() {
            val rounded = java.math.BigDecimal.valueOf(value)
                .setScale(1, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
            return if (key.endsWith("_")) "$rounded %" else rounded
        }
}

data class PlayerWeapon(
    val key: String,
    val level: Int,
    val ascension: Int,
    val refinement: Int,
    val location: String?,
    val locked: Boolean,
) {
    val name: String
        get() = GoodKeyNormalizer.humanize(key)

    val imageKey: String
        get() = GoodKeyNormalizer.normalize(key)
}

object GoodKeyNormalizer {
    fun normalize(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)

    fun humanize(value: String): String {
        val specialName = SPECIAL_NAMES[normalize(value)]
        if (specialName != null) return specialName

        return value
            .replace('_', ' ')
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .trim()
    }

    fun statName(key: String): String = when (key) {
        "hp" -> "HP"
        "hp_" -> "HP %"
        "atk" -> "ATK"
        "atk_" -> "ATK %"
        "def" -> "DEF"
        "def_" -> "DEF %"
        "critRate_" -> "CRIT Rate"
        "critDMG_" -> "CRIT DMG"
        "enerRech_" -> "Energy Recharge"
        "eleMas" -> "Elemental Mastery"
        "heal_" -> "Healing Bonus"
        "physical_dmg_" -> "Physical DMG"
        else -> humanize(key.removeSuffix("_")) + if (key.endsWith("_")) " %" else ""
    }

    private val SPECIAL_NAMES = mapOf(
        "heroswit" to "Hero's Wit",
        "adventurersexperience" to "Adventurer's Experience",
        "wanderersadvice" to "Wanderer's Advice",
    )
}
