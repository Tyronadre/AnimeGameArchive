package de.tyro.genshinapp.model

import java.util.Locale

/**
 * Canonical combat-stat identifiers used by Genshin's data files.
 *
 * The enum names deliberately match the `FIGHT_PROP_*` values persisted in the database, so
 * JPA can store them as readable strings.
 */
enum class FightProperty(
    val optimizerKey: String,
    private vararg val aliases: String,
) {
    FIGHT_PROP_BASE_HP("hp", "base hp"),
    FIGHT_PROP_BASE_ATTACK("atk", "base atk"),
    FIGHT_PROP_BASE_DEFENSE("def", "base def"),
    FIGHT_PROP_CRITICAL("critRate_", "crit rate"),
    FIGHT_PROP_CRITICAL_HURT("critDMG_", "crit dmg", "critical damage"),
    FIGHT_PROP_CHARGE_EFFICIENCY("enerRech_", "energy recharge"),
    FIGHT_PROP_ELEMENT_MASTERY("eleMas", "elemental mastery"),
    FIGHT_PROP_ATTACK_PERCENT("atk_", "atk", "atk%", "atk percentage"),
    FIGHT_PROP_HP_PERCENT("hp_", "hp", "hp%", "hp percentage"),
    FIGHT_PROP_DEFENSE_PERCENT("def_", "def", "def%", "def percentage"),
    FIGHT_PROP_FIRE_ADD_HURT("pyro_dmg_", "pyro dmg bonus", "pyro damage bonus"),
    FIGHT_PROP_WATER_ADD_HURT("hydro_dmg_", "hydro dmg bonus", "hydro damage bonus"),
    FIGHT_PROP_ELEC_ADD_HURT("electro_dmg_", "electro dmg bonus", "electro damage bonus"),
    FIGHT_PROP_ICE_ADD_HURT("cryo_dmg_", "cryo dmg bonus", "cryo damage bonus"),
    FIGHT_PROP_WIND_ADD_HURT("anemo_dmg_", "anemo dmg bonus", "anemo damage bonus"),
    FIGHT_PROP_ROCK_ADD_HURT("geo_dmg_", "geo dmg bonus", "geo damage bonus"),
    FIGHT_PROP_GRASS_ADD_HURT("dendro_dmg_", "dendro dmg bonus", "dendro damage bonus"),
    FIGHT_PROP_PHYSICAL_ADD_HURT(
        "physical_dmg_",
        "physical dmg bonus",
        "physical damage bonus",
        "physical dmg",
        "physical damage",
    ),
    FIGHT_PROP_HEAL_ADD("heal_", "healing bonus"),
    ;

    companion object {
        private val byExternalValue = entries.flatMap { property ->
            buildList {
                add(property.name to property)
                property.aliases.forEach { add(it to property) }
            }
        }.associate { (value, property) -> normalize(value) to property }

        fun fromExternal(value: String?): FightProperty? = value
            ?.takeIf(String::isNotBlank)
            ?.let(::normalize)
            ?.let(byExternalValue::get)

        private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
    }
}
