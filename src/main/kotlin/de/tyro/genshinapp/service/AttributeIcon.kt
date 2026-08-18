package de.tyro.genshinapp.service

enum class AttributeIcon(
    val key: String,
    val fandomName: String,
) {
    ATTACK("attack", "Attack"),
    HEALTH("health", "Health"),
    DEFENSE("defense", "Defense"),
    ELEMENTAL_MASTERY("elemental-mastery", "Elemental Mastery"),
    ENERGY_RECHARGE("energy-recharge", "Energy Recharge"),
    CRITICAL_HIT("critical-hit", "Critical Hit"),
    PYRO("pyro", "Pyro"),
    HYDRO("hydro", "Hydro"),
    ELECTRO("electro", "Electro"),
    CRYO("cryo", "Cryo"),
    ANEMO("anemo", "Anemo"),
    GEO("geo", "Geo"),
    DENDRO("dendro", "Dendro"),
    PHYSICAL("physical", "Physical"),
    ;

    val mediaUrl: String
        get() = "/media/attributes/$key"

    companion object {
        fun fromKey(key: String): AttributeIcon? = entries.firstOrNull { it.key == key.lowercase() }

        fun fromCombatStatKey(statKey: String?): AttributeIcon? = when (statKey) {
            "atk_" -> ATTACK
            "hp_" -> HEALTH
            "def_" -> DEFENSE
            "eleMas" -> ELEMENTAL_MASTERY
            "enerRech_" -> ENERGY_RECHARGE
            "critRate_", "critDMG_" -> CRITICAL_HIT
            "pyro_dmg_" -> PYRO
            "hydro_dmg_" -> HYDRO
            "electro_dmg_" -> ELECTRO
            "cryo_dmg_" -> CRYO
            "anemo_dmg_" -> ANEMO
            "geo_dmg_" -> GEO
            "dendro_dmg_" -> DENDRO
            "physical_dmg_" -> PHYSICAL
            else -> null
        }
    }
}
