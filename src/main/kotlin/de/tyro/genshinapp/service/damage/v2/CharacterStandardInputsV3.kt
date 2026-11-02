package de.tyro.genshinapp.service.damage.v2

/** Standard authoring inputs are supplied by the application, never required in a character file. */
object CharacterStandardInputsV3 {
    val elements = listOf("Pyro", "Hydro", "Electro", "Cryo", "Dendro", "Anemo", "Geo", "Physical")

    fun definitions(element: String): Map<String, DamageFormulaInputV2> {
        fun stat(id: String, label: String, source: String, default: Double = 0.0,
                 max: Double = 200000.0, percent: Boolean = false, min: Double = 0.0) =
            DamageFormulaInputV2(id, label, default, min, max, if (percent) .1 else 1.0,
                if (percent) "percentPoints" else "number", DamageFormulaInitialValueSourceV2(source), section = "internal")
        fun enemy(id: String, label: String, default: Double, min: Double, max: Double) =
            DamageFormulaInputV2(id, label, default, min, max, 1.0,
                if (id == "enemyLevel") "number" else "percentPoints", section = "enemy")
        return (listOf(
            stat("characterLevel", "Character level", "level", 90.0, 90.0, min = 1.0),
            stat("normalTalentLevel", "Normal Attack level", "normalTalent", 1.0, 15.0, min = 1.0),
            stat("skillTalentLevel", "Elemental Skill level", "skillTalent", 1.0, 15.0, min = 1.0),
            stat("burstTalentLevel", "Elemental Burst level", "burstTalent", 1.0, 15.0, min = 1.0),
            stat("constellation", "Constellation", "constellation", max = 6.0).copy(section = "constellations"),
            stat("maxHp", "Max HP", "totalHp"), stat("attack", "Total ATK", "totalAttack", max = 20000.0),
            stat("defense", "Total DEF", "totalDefense", max = 20000.0),
            stat("elementalMastery", "Elemental Mastery", "stat:eleMas", max = 5000.0),
            stat("critRate", "CRIT Rate", "stat:critRate_", 5.0, 1000.0, true),
            stat("critDamage", "CRIT DMG", "stat:critDMG_", 50.0, 1000.0, true),
            stat("energyRecharge", "Energy Recharge", "stat:enerRech_", 100.0, 1000.0, true),
            stat("elementalDamageBonus", "$element DMG Bonus", "stat:${element.lowercase()}_dmg_", max = 1000.0, percent = true),
            stat("physicalDamageBonus", "Physical DMG Bonus", "stat:physical_dmg_", max = 1000.0, percent = true),
            stat("baseAttack", "Base ATK", "baseAttack"), stat("baseHp", "Base HP", "baseHp"),
            stat("baseDefense", "Base DEF", "baseDefense"),
        ) + elements.filterNot { it.equals(element, true) || it == "Physical" }.map {
            stat("${it.lowercase()}DamageBonus", "$it DMG Bonus", "stat:${it.lowercase()}_dmg_", max = 1000.0, percent = true)
        } + listOf(
            enemy("enemyLevel", "Enemy level", 90.0, 1.0, 120.0),
            enemy("enemyResistance", "Enemy $element RES", 10.0, -200.0, 200.0),
            enemy("enemyPhysicalResistance", "Enemy Physical RES", 10.0, -200.0, 200.0),
            enemy("defenseReduction", "Additional DEF reduction", 0.0, 0.0, 100.0),
            enemy("defenseIgnore", "Additional DEF ignore", 0.0, 0.0, 100.0),
        )).associateBy { it.id }
    }

    fun complete(definition: DamageFormulaProfileV2): DamageFormulaProfileV2 {
        val standards = definitions(definition.element)
        require(definition.inputDefaults.keys.all(standards::containsKey)) { "Unknown standard input default in '${definition.key}'" }
        val inputs = definition.inputs.associateByTo(linkedMapOf()) { it.id }
        val bindings = definition.statBindings.toMutableMap()
        standards.forEach { (logical, template) ->
            val isStat = template.initialValueSource != null
            val id = if (isStat) bindings[logical] ?: inputs[logical]?.id ?: inputs.values.firstOrNull {
                it.initialValueSource?.valueKey == template.initialValueSource?.valueKey
            }?.id ?: logical else logical
            if (isStat) bindings[logical] = id
            inputs.putIfAbsent(id, template.copy(id = id))
            definition.inputDefaults[logical]?.let { value -> inputs[id] = inputs.getValue(id).copy(defaultValue = value) }
        }
        require(bindings.values.all(inputs::containsKey)) { "Undefined stat binding in '${definition.key}'" }
        bindings["${definition.element.lowercase()}DamageBonus"] = bindings.getValue("elementalDamageBonus")
        return definition.copy(inputs = inputs.values.toList(), statBindings = bindings)
    }
}
