package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.TravelerIdentity
import org.springframework.stereotype.Service

internal data class EquippedEffectItemV2(val owner: String, val key: String, val slot: String)

/** Binds reusable artifact expressions to equipped wearers and the current damage recipient. */
@Service
class DamageFormulaArtifactServiceV2(private val objectMapper: ObjectMapper) {
    private val definitions by reloadableFormulaV2 {
        val damageTypes = DamageTypeRegistryV2(objectMapper)
        val sets = DamageFormulaResourcesV2.resources("classpath*:data/damage-formulas-v2/artifacts/*.json")
            .map { resource -> resource.inputStream.use { stream ->
                val tree = objectMapper.readTree(stream)
                require(!tree.has("damageTypes")) { "Artifact damage types belong in damage-types.json" }
                val set = objectMapper.treeToValue(tree, DamageArtifactSetV2::class.java)
                set.copy(damageTypes = damageTypes.resolve(set.effects.flatMap { it.formulas }.map { it.damageType }))
            } }
        require(sets.map { it.key }.distinct().size == sets.size) { "Duplicate artifact formula keys" }
        sets.forEach { set ->
            require(set.schemaVersion == 2 && set.key == GoodKeyNormalizer.normalize(set.key))
            require(set.effects.map { it.effect.id }.distinct().size == set.effects.size)
            require(set.effects.all { it.pieces in listOf(1, 2, 4) && it.recipient in listOf("wearer", "party", "others") })
        }
        sets.associateBy { it.key }
    }

    fun allDefinitions(): Collection<DamageArtifactSetV2> = definitions.values

    fun attach(profile: DamageFormulaProfileV2, artifacts: List<PlayerArtifact>): DamageFormulaProfileV2 {
        return bindEquipment(profile, artifacts.map { EquippedEffectItemV2(it.location.orEmpty(), it.setKey, it.slotKey) }, definitions)
    }

    // Shared expression binding for equipped effects; weapons supply a single equipped tier.
    internal fun bindEquipment(
        profile: DamageFormulaProfileV2, equipment: List<EquippedEffectItemV2>, definitions: Map<String, DamageArtifactSetV2>, kind: String = "artifact", refinements: Map<String, Int> = emptyMap()
    ): DamageFormulaProfileV2 {
        val members = profile.teamMembers.filter { it.key in profile.selectedTeam }.ifEmpty {
            listOf(DamageTeamMemberV2(profile.characterKey, profile.name, profile.element, profile.moonsignContribution))
        }
        val minimumTier = if (kind == "weapon") 1 else 2
        val inputs = profile.inputs.associateByTo(linkedMapOf()) { it.id }
        val effects = profile.effects.toMutableList()
        val talents = profile.talents.toMutableList()
        val types = profile.damageTypes.associateByTo(linkedMapOf()) { it.id }
        val context = profile.teamContext.toMutableMap()
        val bindings = members.associate { member ->
            member.key to (if (member.key == profile.characterKey) profile.statBindings + listOf(
                "attack", "elementalMastery", "critRate", "critDamage", "elementalDamageBonus"
            ).associateWith { it }
            else profile.supportProfiles.firstOrNull { it.key == member.key }?.statBindings.orEmpty()).plus(profile.characterStatInputs[member.key].orEmpty()).toMutableMap()
        }

        fun statInput(character: String, stat: String): String {
            return bindings.getValue(character).getOrPut(stat) {
                val id = "${kind}Stat.$character.$stat"
                val spec = statDefinition(stat)
                val name = members.first { it.key == character }.name
                inputs[id] = DamageFormulaInputV2(
                    id,
                    "$name · ${spec.label}",
                    spec.defaultValue,
                    spec.min,
                    spec.max,
                    spec.step,
                    spec.unit,
                    spec.initialValueSource?.copy(characterKey = character),
                    section = "$kind:$character",
                    hintTag = spec.hintTag,
                    description = spec.description
                )
                id
            }
        }

        val loadouts = members.map { wearer ->
            val equipped = equipment.filter {
                TravelerIdentity.canonicalCharacterKey(it.owner) == TravelerIdentity.canonicalCharacterKey(wearer.key)
            }
            require(equipped.map { it.slot.lowercase() }.distinct().size == equipped.size) {
                "${wearer.name} has multiple equipped items in the same slot"
            }
            val counts = equipped.groupingBy { GoodKeyNormalizer.normalize(it.key) }.eachCount().toSortedMap()
            counts.forEach setLoop@{ (key, pieces) ->
                val set = definitions[key] ?: return@setLoop
                val prefix = "$kind.${wearer.key}.$key."
                val countContext = "${prefix}pieces"
                context[countContext] = pieces.toDouble()
                val weapon = if (wearer.key == profile.characterKey) profile.weaponType
                else profile.supportProfiles.firstOrNull { it.key == wearer.key }?.weaponType ?: wearer.weaponType
                val localContext = mapOf(
                    "meleeWeapon" to if (weapon.lowercase() in listOf("sword", "claymore", "polearm")) 1.0 else 0.0,
                    "rangedWeapon" to if (weapon.lowercase() in listOf("bow", "catalyst")) 1.0 else 0.0,
                    "onField" to if (wearer.key == profile.characterKey) 1.0 else 0.0,
                    "offField" to if (wearer.key != profile.characterKey) 1.0 else 0.0,
                    "sameElementAllies" to members.count { it.key != wearer.key && it.element == wearer.element }.toDouble(),
                    "differentElementAllies" to members.count { it.key != wearer.key && it.element != wearer.element }.toDouble(),
                ) + profile.teamContext
                localContext.forEach { (name, value) -> context[prefix + name] = value }
                // Include only controls referenced by an available tier (some 2-piece
                // bonuses are conditional too, for example Obsidian Codex).
                val neededInputs = mutableSetOf<String>()
                fun collectInputs(node: com.fasterxml.jackson.databind.JsonNode) {
                    if (node.isObject && node.path("input").asText().startsWith("local:")) {
                        neededInputs += node.path("input").asText().substringAfter(':')
                    }
                    if (node.isContainerNode) node.forEach(::collectInputs)
                }
                set.effects.filter { it.pieces >= minimumTier && pieces >= it.pieces }.forEach {
                    collectInputs(objectMapper.valueToTree(it))
                }
                set.inputs.filter { it.id in neededInputs }.forEach { input ->
                    inputs[prefix + input.id] = input.copy(
                        id = prefix + input.id,
                        label = "${wearer.name} · ${input.label}",
                        section = "$kind:${wearer.key}",
                        defaultValue = if (input.id == "refinement" && kind == "weapon") refinements.getValue(wearer.key).toDouble()
                        else input.defaultContext?.let { localContext.getValue(it) } ?: input.defaultValue)
                }

                fun expression(node: FormulaExpressionV2, recipient: String): FormulaExpressionV2 {
                    if (node.type == "context") return FormulaExpressionV2("constant", value = localContext[node.input] ?: 0.0)
                    val boundInput = node.input?.let { id ->
                        when {
                            node.type != "input" -> id
                            id.startsWith("wearer:") -> statInput(wearer.key, id.substringAfter(':'))
                            id.startsWith("source:") -> statInput(wearer.key, id.substringAfter(':'))
                            id.startsWith("recipient:") -> statInput(recipient, id.substringAfter(':'))
                            id.startsWith("local:") -> prefix + id.substringAfter(':')
                            else -> id
                        }
                    }
                    return node.copy(input = boundInput, operands = node.operands.map { expression(it, recipient) })
                }

                fun contribution(value: FormulaContributionV2?, recipient: String) = value?.copy(expression = expression(value.expression, recipient))
                fun conditions(values: List<EffectActivationConditionV2>) = values.map { condition ->
                    condition.copy(input = condition.input?.let {
                        if (it.startsWith("local:") || it.startsWith("context:")) prefix + it.substringAfter(':') else it
                    })
                }

                fun modifiers(values: List<FormulaModifierV2>, recipient: String) = values.map { modifier ->
                    modifier.copy(
                        conditions = conditions(modifier.conditions), requiresEffects = modifier.requiresEffects.map { prefix + it },
                        targets = modifier.targets.copy(elements = modifier.targets.elements.flatMap { element ->
                            when (element) {
                                "wearer" -> listOf(wearer.element)
                                "recipient" -> listOf(profile.element)
                                "reactionElement" -> listOf("Hydro", "Pyro", "Cryo", "Electro")
                                else -> listOf(element)
                            }
                        }),
                    ).bindExpressions { expression(it, recipient) }
                }
                set.effects.filter { it.pieces >= minimumTier && pieces >= it.pieces }.forEach { template ->
                    val origin = DamageArtifactOriginV2(wearer.key, key, set.name, pieces, template.pieces)
                    val source = template.effect
                    val recipients = when (template.recipient) {
                        "party" -> members.map { it.key }
                        "others" -> members.filter { it.key != wearer.key }.map { it.key }
                        else -> listOf(wearer.key)
                    }
                    val statChanges = recipients.flatMap { recipient ->
                        source.statChanges.map { change ->
                            change.copy(
                                stat = statInput(recipient, change.stat), value = expression(change.value, recipient),
                                conditions = conditions(change.conditions), requiresEffects = change.requiresEffects.map { prefix + it },
                            )
                        }
                    }
                    effects += source.copy(
                        id = prefix + source.id, category = "$kind:${wearer.key}",
                        source = "${wearer.name} · ${set.name}", artifactOrigin = origin.takeIf { kind == "artifact" },
                        activation = source.activation.copy(
                            conditions = conditions(source.activation.conditions) + EffectActivationConditionV2("contextAtLeast", countContext, template.pieces.toDouble())
                        ),
                        requiresEffects = source.requiresEffects.map { prefix + it }, statChanges = statChanges,
                        stackingPriority = source.stackingPriority?.let { expression(it, wearer.key) },
                        formulaModifiers = if (profile.characterKey in recipients) modifiers(source.formulaModifiers, profile.characterKey) else emptyList(),
                        metrics = source.metrics.map { it.copy(expression = expression(it.expression, wearer.key), conditions = conditions(it.conditions)) },
                    )
                    if (template.formulas.isNotEmpty()) {
                        val boundTypes = mutableMapOf<String, String>()
                        set.damageTypes.forEach { type ->
                            // A stat-dependent shared type must read this wearer's build, even if another wearer uses it too.
                            val typeId = if (type.innateDamageBonus != null || type.appliesElementalDamageBonus) "${prefix}type.${type.id}" else type.id
                            boundTypes[type.id] = typeId
                            val resistanceInput = DamageTypeRegistryV2.resistanceInput(type, profile.element)
                            types.putIfAbsent(typeId, type.copy(id = typeId, resistanceInput = resistanceInput,
                                innateDamageBonus = contribution(type.innateDamageBonus, wearer.key),
                                elementalBonusInput = if (type.appliesElementalDamageBonus) statInput(wearer.key, "${type.element.lowercase()}DamageBonus") else null))
                            if (resistanceInput !in inputs) inputs[resistanceInput] = DamageFormulaInputV2(
                                resistanceInput, "Enemy ${type.element} RES", 10.0, -100.0, 150.0, 0.1, "percentPoints", section = "enemy"
                            )
                        }
                        talents += DamageTalentV2(prefix + source.id, "${wearer.name} · ${set.name}", template.formulas.map { formula ->
                            formula.copy(
                                id = prefix + formula.id, damageType = boundTypes.getValue(formula.damageType),
                                requiredEffects = listOf(prefix + source.id), base = expression(formula.base, wearer.key)
                            )
                        })
                    }
                }
            }
            DamageArtifactLoadoutV2(wearer.key, wearer.name, equipped.size, counts.mapNotNull { (key, count) ->
                val definition = definitions[key]
                val activatable = definition?.effects?.any { it.pieces >= 2 && count >= it.pieces } ?: (count >= 2)
                if (!activatable) null
                else DamageEquippedArtifactSetV2(key, definition?.name ?: key, count, definition != null)
            })
        }
        return profile.copy(
            inputs = inputs.values.toList(),
            effects = effects,
            talents = talents,
            damageTypes = types.values.toList(),
            teamContext = context,
            artifactLoadouts = if (kind == "artifact") loadouts else profile.artifactLoadouts,
            teamStatInputs = bindings.values.flatMap { it.entries }.groupBy({ it.key }, { it.value })
        )
    }

    private fun statDefinition(stat: String): DamageFormulaInputV2 {
        val source = when (stat) {
            "attack" -> "totalAttack"
            "baseAttack" -> "baseAttack"
            "maxHp" -> "totalHp"
            "baseHp" -> "baseHp"
            "elementalMastery" -> "stat:eleMas"
            "critRate" -> "stat:critRate_"
            "critDamage" -> "stat:critDMG_"
            "energyRecharge" -> "stat:enerRech_"
            "healingBonus" -> "stat:heal_"
            "physicalDamageBonus" -> "stat:physical_dmg_"
            else -> null
        }
        val percent = stat in setOf("critRate", "critDamage", "energyRecharge", "healingBonus", "incomingHealing", "shieldStrength", "physicalDamageBonus")
        return DamageFormulaInputV2(
            stat,
            stat.replace(Regex("([a-z])([A-Z])"), "$1 $2"),
            when (stat) {
                "energyRecharge" -> 100.0; "critRate" -> 5.0; "critDamage" -> 50.0; else -> 0.0
            },
            0.0,
            if (percent) 1000.0 else 100000.0,
            if (percent) 0.1 else 1.0,
            if (percent) "percentPoints" else "number",
            source?.let { DamageFormulaInitialValueSourceV2(it) },
            hintTag = stat,
            description = if (source == null) "Manual value: this base stat is not supplied by the current build importer. Set bonuses are applied separately." else null
        )
    }
}
