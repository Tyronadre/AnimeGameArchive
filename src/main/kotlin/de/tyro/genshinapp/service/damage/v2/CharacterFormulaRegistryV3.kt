package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

/** One authored schema per character; the calculator continues to consume compiled V2 profiles. */
@Service
class CharacterFormulaRegistryV3(private val mapper: ObjectMapper) {
    private val damageTypes = DamageTypeRegistryV2(mapper)
    private val hexereiCharacters by reloadableFormulaV2 {
        DamageFormulaResourcesV2.resource("data/damage-formulas-v2/team-rules.json").inputStream.use {
            mapper.readValue(it, DamageTeamRulesV2::class.java).hexereiCharacters.toSet()
        }
    }
    val definitions: Map<String, DamageFormulaProfileV2> by reloadableFormulaV2 {
        require(DamageFormulaResourcesV2.resources("classpath*:data/damage-formulas-v2/teammates/*.json").isEmpty()) {
            "Legacy teammate definitions remain. Use a single schema-3 character file and remove teammates/*.json."
        }
        val profiles = DamageFormulaResourcesV2.resources("classpath*:data/damage-formulas-v2/*.json").mapNotNull { resource ->
            val tree = resource.inputStream.use { mapper.readTree(it) }
            if (!tree.has("characterKey")) return@mapNotNull null
            require(!tree.has("damageTypes")) { "Character damage types are shared: edit damage-types.json and select a type on each hit." }
            val definition = mapper.treeToValue(tree, DamageFormulaProfileV2::class.java)
            require(definition.schemaVersion == 3) { "Character '${definition.key}' must use unified schema 3" }
            require(definition.key == definition.characterKey) { "Character key mismatch in '${definition.key}'" }
            require(resource.filename == "${definition.key}.json") { "Character file must be named '${definition.key}.json'" }
            require(definition.inputs.map { it.id }.distinct().size == definition.inputs.size) { "Duplicate inputs in '${definition.key}'" }
            require(definition.effects.map { it.id }.distinct().size == definition.effects.size) { "Duplicate effects in '${definition.key}'" }
            val resolved = damageTypes.resolve(definition.talents.flatMap { it.formulas }.map { it.damageType }).map { type ->
                type.copy(resistanceInput = DamageTypeRegistryV2.resistanceInput(type, definition.element))
            }
            complete(definition.copy(damageTypes = resolved))
        }
        require(profiles.map { it.key }.distinct().size == profiles.size) { "Duplicate character definitions" }
        profiles.sortedBy { it.name }.associateBy { it.key }
    }

    fun available(key: String) = definitions[key]?.talents?.any { it.formulas.isNotEmpty() } == true
    fun solo(key: String, requireDamage: Boolean = true): DamageFormulaProfileV2? = definitions[key]?.takeIf { !requireDamage || available(key) }?.let { definition ->
        val members = listOf(member(definition))
        val context = context(members)
        val bound = CharacterEffectBinderV3(listOf(definition), key, context).bind(definition)
        definition.copy(schemaVersion = 2, inputs = bound.inputs, effects = bound.effects, talents = bound.talents,
            damageTypes = bound.damageTypes, statBindings = bound.statBindings, selectedTeam = setOf(key),
            teamMembers = members, teamContext = context, teamStatInputs = bound.statBindings.mapValues { listOf(it.value) })
    }
    fun member(definition: DamageFormulaProfileV2) = DamageTeamMemberV2(definition.key, definition.name, definition.element,
        definition.moonsignContribution, weaponType = definition.weaponType, calculatorAvailable = definition.talents.any { it.formulas.isNotEmpty() },
        region = definition.characterDataResource.takeIf { it.isNotBlank() }?.let { path ->
            DamageFormulaResourcesV2.resource(path).inputStream.use { mapper.readTree(it).path("region").asText("") }
        }.orEmpty())

    fun context(members: List<DamageTeamMemberV2>): Map<String, Double> =
        members.groupingBy { it.element.lowercase() }.eachCount().mapKeys { "${it.key}Count" }.mapValues { it.value.toDouble() } +
            mapOf("hexereiCount" to members.count { it.key in hexereiCharacters }.toDouble(),
                "pyroElectroOnly" to if (members.all { it.element.lowercase() in setOf("pyro", "electro") }) 1.0 else 0.0,
                "teamSize" to members.size.toDouble(), "moonsignLevel" to members.sumOf { it.moonsignContribution }.coerceAtMost(2).toDouble(),
                "hydroCryoCount" to members.count { it.element.equals("Hydro", true) || it.element.equals("Cryo", true) }.toDouble()) +
            members.flatMap { listOf("character.${it.key}.hexerei" to if (it.key in hexereiCharacters) 1.0 else 0.0,
                "character.${it.key}.pyroElectro" to if (it.element.lowercase() in setOf("pyro", "electro")) 1.0 else 0.0,
                "character.${it.key}.nonFontaineAllies" to members.count { other -> other.key != it.key && other.region.isNotBlank() && !other.region.equals("Fontaine", true) }.toDouble(),
                "character.${it.key}.onField" to if (it == members.first()) 1.0 else 0.0,
                "character.${it.key}.offField" to if (it != members.first()) 1.0 else 0.0) }.toMap()

    fun generic(member: DamageTeamMemberV2) = complete(DamageFormulaProfileV2(3, member.key, member.key, member.name, member.element,
        inputs = emptyList(), weaponType = member.weaponType))

    fun withDamageTypes(profile: DamageFormulaProfileV2, ids: Collection<String>): DamageFormulaProfileV2 {
        val missing = ids.distinct().filter { id -> profile.damageTypes.none { it.id == id } }
        if (missing.isEmpty()) return profile
        val extra = damageTypes.resolve(missing).map { it.copy(resistanceInput = DamageTypeRegistryV2.resistanceInput(it, profile.element)) }
        val completed = complete(profile.copy(damageTypes = profile.damageTypes + extra))
        val boundTypes = CharacterEffectBinderV3(listOf(completed), completed.key, completed.teamContext)
            .bind(completed.copy(damageTypes = extra, effects = emptyList(), talents = emptyList())).damageTypes
        return completed.copy(damageTypes = profile.damageTypes + boundTypes)
    }

    private fun complete(definition: DamageFormulaProfileV2): DamageFormulaProfileV2 {
        val standard = CharacterStandardInputsV3.complete(definition)
        val inputs = standard.inputs.associateByTo(linkedMapOf()) { it.id }
        val bindings = standard.statBindings
        val foreignElements = definition.damageTypes.filter { it.appliesElementalDamageBonus && !it.element.equals(definition.element, true) }.map { it.element }.distinct()
        definition.damageTypes.forEach { type -> inputs.putIfAbsent(type.resistanceInput,
            DamageFormulaInputV2(type.resistanceInput, "Enemy ${type.element} RES", 10.0, -200.0, 200.0, 1.0, "percentPoints", section = "enemy")) }
        foreignElements.forEach { element ->
            val id = bindings.getValue("${element.lowercase()}DamageBonus")
            inputs[id] = inputs.getValue(id).copy(label = "$element DMG Bonus", section = "build")
        }
        return standard.copy(inputs = inputs.values.toList())
    }
}

/** Resolves portable source/recipient references and local identifiers before arithmetic evaluation. */
class CharacterEffectBinderV3(
    private val members: List<DamageFormulaProfileV2>, private val damageOwner: String,
    private val context: Map<String, Double>,
) {
    private val byKey = members.associateBy { it.key }
    private val scopes = setOf("self", "party", "others", "activeCharacter", "enemy")
    private fun id(owner: String, local: String) = if (owner == damageOwner) local else "character.$owner.$local"
    private fun inputId(owner: String, local: String): String {
        val aliases = setOf("attack", "elementalMastery", "critRate", "critDamage", "elementalDamageBonus", "characterLevel",
            "normalTalentLevel", "skillTalentLevel", "burstTalentLevel")
        return if (owner == damageOwner) byKey.getValue(owner).statBindings.entries.firstOrNull {
            it.key in aliases && it.value == local
        }?.key ?: local else id(owner, local)
    }
    private fun stat(owner: String, name: String): String {
        val member = byKey.getValue(owner)
        val local = member.statBindings[name] ?: name.takeIf { candidate -> member.inputs.any { it.id == candidate } }
            ?: throw IllegalArgumentException("Undefined stat '$name' for '$owner'")
        return inputId(owner, local)
    }
    private fun input(owner: String, recipient: String, name: String): String = when {
        name.startsWith("battle:") -> name.substringAfter(':')
        name.startsWith("source:") -> stat(owner, name.substringAfter(':'))
        name.startsWith("recipient:") -> stat(recipient, name.substringAfter(':'))
        name.startsWith("local:") -> inputId(owner, name.substringAfter(':'))
        else -> inputId(owner, name)
    }
    private fun expression(e: FormulaExpressionV2, owner: String, recipient: String): FormulaExpressionV2 = when (e.type) {
        "input" -> e.copy(input = input(owner, recipient, requireNotNull(e.input)))
        "talentParameter" -> e.copy(characterKey = e.characterKey ?: owner,
            levelInput = input(owner, recipient, requireNotNull(e.levelInput)))
        "context" -> FormulaExpressionV2("constant", value = when(e.input) {
            "onField" -> if (owner == damageOwner) 1.0 else 0.0
            "offField" -> if (owner == damageOwner) 0.0 else 1.0
            "recipientHexerei" -> context.getOrDefault("character.$recipient.hexerei", 0.0)
            "sourceHexerei" -> context.getOrDefault("character.$owner.hexerei", 0.0)
            "sourceNonFontaineAllies" -> context.getOrDefault("character.$owner.nonFontaineAllies", 0.0)
            "recipientPyroElectro" -> context.getOrDefault("character.$recipient.pyroElectro", 0.0)
            else -> context.getOrDefault(e.input, 0.0)
        })
        else -> e.copy(operands = e.operands.map { expression(it, owner, recipient) })
    }
    private fun contribution(c: FormulaContributionV2?, owner: String, recipient: String) = c?.copy(expression = expression(c.expression, owner, recipient))
    private fun conditions(list: List<EffectActivationConditionV2>, owner: String, recipient: String) = list.map { condition ->
        if (condition.type in setOf("inputAtLeast", "inputAtMost")) condition.copy(input = input(owner, recipient, requireNotNull(condition.input)))
        else if (condition.type == "contextAtLeast" && condition.input in setOf("onField", "offField"))
            condition.copy(input = "character.$owner.${condition.input}")
        else if (condition.type == "contextAtLeast" && condition.input in setOf("recipientHexerei", "sourceHexerei"))
            condition.copy(input = "character.${if (condition.input == "recipientHexerei") recipient else owner}.hexerei")
        else if (condition.type == "contextAtLeast" && condition.input == "recipientPyroElectro")
            condition.copy(input = "character.$recipient.pyroElectro") else condition
    }
    private fun recipients(scope: String, owner: String, exclude: Boolean): List<String> {
        require(scope in scopes) { "Unknown recipient '$scope'" }
        val selected = when(scope) { "self" -> listOf(owner); "others" -> byKey.keys.filter { it != owner }
            "activeCharacter", "enemy" -> listOf(damageOwner); else -> byKey.keys.toList() }
        return selected.filterNot { exclude && it == owner }
    }
    fun bind(source: DamageFormulaProfileV2): DamageFormulaProfileV2 {
        val owner = source.key
        val effects = source.effects.map { effect ->
            require(effect.recipient in scopes) { "Unknown effect recipient '${effect.recipient}'" }
            val changes = effect.statChanges.flatMap { change ->
                val scope = change.recipient ?: effect.recipient
                require(scope != "enemy") { "Enemy debuffs use formula mitigation modifiers, not character stat changes" }
                require(!(change.stat.startsWith("local:") || change.stat.startsWith("source:")) || scope == "self") {
                    "Source/local stat changes must target self; use recipient:<stat> for a party buff"
                }
                recipients(scope, owner, effect.excludeSource || change.excludeSource).map { recipient ->
                    change.copy(stat = when {
                        change.stat.startsWith("source:") -> stat(owner, change.stat.substringAfter(':'))
                        change.stat.startsWith("local:") -> inputId(owner, change.stat.substringAfter(':'))
                        else -> stat(recipient, change.stat.removePrefix("recipient:"))
                    }, value = expression(change.value, owner, recipient), conditions = conditions(change.conditions, owner, recipient),
                        requiresEffects = change.requiresEffects.map { id(owner, it) })
                }
            }
            val modifiers = effect.formulaModifiers.mapNotNull { m ->
                val recipients = recipients(m.recipient ?: effect.recipient, owner, effect.excludeSource || m.excludeSource)
                if (damageOwner !in recipients) return@mapNotNull null
                if (owner != damageOwner && m.targets.formulaIds.any { !it.startsWith("recipient:") }) return@mapNotNull null
                m.copy(targets = m.targets.copy(formulaIds = m.targets.formulaIds.map { it.removePrefix("recipient:") }),
                    conditions = conditions(m.conditions, owner, damageOwner), requiresEffects = m.requiresEffects.map { id(owner, it) },
                ).bindExpressions { expression(it, owner, damageOwner) }
            }
            effect.copy(id = id(owner, effect.id), sourceCharacterKey = owner, statChanges = changes, formulaModifiers = modifiers,
                requiresEffects = effect.requiresEffects.map { id(owner, it) },
                ownershipRequirements = effect.ownershipRequirements.mapKeys { input(owner, owner, it.key) },
                activation = effect.activation.copy(conditions = conditions(effect.activation.conditions, owner, owner)),
                stackingPriority = effect.stackingPriority?.let { expression(it, owner, owner) },
                metrics = effect.metrics.map { it.copy(expression = expression(it.expression, owner, owner), conditions = conditions(it.conditions, owner, owner)) })
        }
        val talents = if (owner != damageOwner) emptyList() else source.talents.map { talent -> talent.copy(formulas = talent.formulas.map { f ->
            f.copy(base = expression(f.base, owner, owner), requiredEffects = f.requiredEffects.map { id(owner, it) }, excludedEffects = f.excludedEffects.map { id(owner, it) },
                damageBonus = contribution(f.damageBonus, owner, owner),
                defenseMitigation = f.defenseMitigation?.copy(reduction = contribution(f.defenseMitigation.reduction, owner, owner), ignore = contribution(f.defenseMitigation.ignore, owner, owner)),
                resistanceMitigation = f.resistanceMitigation?.copy(reduction = contribution(f.resistanceMitigation.reduction, owner, owner)),
                crit = f.crit?.copy(additionalRate = contribution(f.crit.additionalRate, owner, owner), additionalDamage = contribution(f.crit.additionalDamage, owner, owner)))
        }) }
        return source.copy(schemaVersion = 2, inputs = source.inputs.filter { definition ->
            owner == damageOwner || !(definition.section == "enemy" || definition.id in setOf("defenseReduction", "defenseIgnore"))
        }.map { definition -> definition.copy(id = inputId(owner, definition.id),
            initialValueSource = definition.initialValueSource?.copy(characterKey = owner)) }, effects = effects, talents = talents,
            damageTypes = source.damageTypes.map { type -> type.copy(innateDamageBonus = contribution(type.innateDamageBonus, owner, owner),
                elementalBonusInput = if (type.appliesElementalDamageBonus && !type.element.equals(source.element, true)) stat(owner, "${type.element.lowercase()}DamageBonus") else null) },
            statBindings = source.statBindings.mapValues { inputId(owner, it.value) })
    }
}
