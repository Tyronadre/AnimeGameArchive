package de.tyro.genshinapp.controller

import de.tyro.genshinapp.service.catalog.WeaponDefinition
import de.tyro.genshinapp.service.planning.SavedWeaponTarget
import de.tyro.genshinapp.configuration.LocalizedMessages
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterProgressForm
import de.tyro.genshinapp.model.CharacterTalentKind
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.TravelerAppearance
import de.tyro.genshinapp.model.TravelerElement
import de.tyro.genshinapp.model.TravelerIdentity
import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.catalog.ArtifactCatalogService
import de.tyro.genshinapp.service.artifact.ArtifactOptimizationService
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.content.FandomImageUrlResolver
import de.tyro.genshinapp.service.planning.CharacterTargetService
import de.tyro.genshinapp.service.planning.CharacterWeaponTargetService
import de.tyro.genshinapp.service.player.PlayerEquipmentService
import de.tyro.genshinapp.service.planning.PlayerPlanningService
import de.tyro.genshinapp.service.player.PlayerSnapshotStore
import de.tyro.genshinapp.service.player.TravelerService
import de.tyro.genshinapp.service.artifact.OptimizerCombatStatService
import de.tyro.genshinapp.service.catalog.WeaponCatalogService
import de.tyro.genshinapp.service.catalog.WeaponDataService
import de.tyro.genshinapp.service.importer.HoyolabCharacterPageService
import de.tyro.genshinapp.service.planning.WeaponPlanningService
import de.tyro.genshinapp.service.damage.DamageCalculatorService
import de.tyro.genshinapp.service.damage.v2.DamageFormulaServiceV2
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class CharacterController(
    private val catalogService: CharacterCatalogService,
    private val snapshotStore: PlayerSnapshotStore,
    private val planningService: PlayerPlanningService,
    private val targetService: CharacterTargetService,
    private val characterWeaponTargetService: CharacterWeaponTargetService,
    private val equipmentService: PlayerEquipmentService,
    private val artifactCatalogService: ArtifactCatalogService,
    private val artifactOptimizationService: ArtifactOptimizationService,
    private val optimizerCombatStatService: OptimizerCombatStatService,
    private val weaponCatalogService: WeaponCatalogService,
    private val weaponDataService: WeaponDataService,
    private val weaponPlanningService: WeaponPlanningService,
    private val travelerService: TravelerService,
    private val messages: LocalizedMessages,
    private val damageCalculatorService: DamageCalculatorService,
    private val damageFormulaServiceV2: DamageFormulaServiceV2,
    private val hoyolabCharacterPageService: HoyolabCharacterPageService,
    private val fandomImageUrlResolver: FandomImageUrlResolver,
) {
    @GetMapping("/api/characters/{key}", "/characters/{key}/json")
    @ResponseBody
    fun characterJson(@PathVariable key: String): CharacterDefinition =
        hoyolabCharacterPageService.enrichExact(key)
            ?: catalogService.findCharacter(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Character not found")

    @GetMapping("/characters")
    fun characters(
        @RequestParam(required = false, defaultValue = "") query: String,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val normalizedQuery = query.trim()
        val travelerSelection = travelerService.selection(principal.id)
        val characters = catalogService.getCharacters()
            .map { character ->
                if (character.key == TravelerIdentity.KEY) {
                    catalogService.findTravelerAppearance(travelerSelection.appearance).copy(
                        element = travelerSelection.element.displayName,
                    )
                } else {
                    character
                }
            }
        val snapshot = snapshotStore.current(principal.id)
        val ownershipOverrides = targetService.ownershipOverrides(principal.id)
        val ownershipByCharacter = characters.associate { character ->
            val normalizedKey = TravelerIdentity.canonicalCharacterKey(character.key)
            val importedOwnership = snapshot?.let {
                planningService.findCharacterState(it, character.key) != null
            } ?: false
            character.key to (ownershipOverrides[normalizedKey] ?: importedOwnership)
        }
        val characterCards = characters.map { character ->
            CharacterCatalogCard(
                character = character,
                owned = ownershipByCharacter[character.key] == true,
                elementKey = GoodKeyNormalizer.normalize(character.element.orEmpty()),
                elementIconUrl = character.element
                    ?.let { element -> ELEMENT_NAMES.firstOrNull { it.equals(element, true) } }
                    ?.let(fandomImageUrlResolver::elementIconUrl),
                weaponTypeKey = weaponTypeKey(character.weapon),
                searchText = buildList {
                    add(character.name)
                    character.title?.let(::add)
                    character.element?.let(::add)
                    character.region?.let(::add)
                    character.weapon?.let(::add)
                    character.affiliation?.let(::add)
                    if (character.key == TravelerIdentity.KEY) {
                        addAll(TravelerElement.entries.map(TravelerElement::displayName))
                        addAll(TravelerAppearance.entries.map(TravelerAppearance::key))
                    }
                }.joinToString(" "),
            )
        }

        model.addAttribute("characters", characters)
        model.addAttribute("characterCards", characterCards)
        model.addAttribute("ownershipByCharacter", ownershipByCharacter)
        model.addAttribute("elementFilters", elementFilterOptions())
        model.addAttribute("characterWeaponFilters", weaponFilterOptions())
        model.addAttribute("query", normalizedQuery)
        model.addAttribute("totalCharacters", characters.size)
        return "home"
    }

    @GetMapping("/characters/{key}")
    fun character(
        @PathVariable key: String,
        @ModelAttribute("progress") progressForm: CharacterProgressForm,
        @RequestParam requestParameters: Map<String, String>,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val normalizedRouteKey = GoodKeyNormalizer.normalize(key)
        TravelerAppearance.fromKey(normalizedRouteKey)
            ?.takeIf { normalizedRouteKey in setOf("aether", "lumine") }
            ?.let { appearance ->
                travelerService.selectAppearance(principal.id, appearance)
                return "redirect:/characters/${TravelerIdentity.KEY}"
            }

        hoyolabCharacterPageService.enrichExact(normalizedRouteKey)
        val baseCharacter = catalogService.findCharacter(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Character not found")
        val travelerSelection = travelerService.selection(principal.id)
        val isTraveler = baseCharacter.key == TravelerIdentity.KEY
        if (isTraveler) {
            hoyolabCharacterPageService.enrichExact(travelerSelection.element.variantKey)
        }
        val character = if (isTraveler) {
            catalogService.findTraveler(
                travelerSelection.element,
                travelerSelection.appearance,
            )
        } else {
            baseCharacter
        }
        val snapshot = snapshotStore.current(principal.id)
        val playerState = snapshot?.let {
            planningService.findCharacterState(it, baseCharacter.key)
        }
        val savedTarget = targetService.find(principal.id, baseCharacter.key)
        if (requestParameters.isEmpty()) {
            if (isTraveler) {
                if (playerState != null) {
                    if (travelerSelection.elementConfigured) {
                        progressForm.applyShared(playerState)
                    } else {
                        progressForm.apply(playerState)
                    }
                }
                savedTarget?.applySharedTo(progressForm)
                travelerService.progress(principal.id, travelerSelection.element)
                    ?.applyTo(progressForm)
            } else {
                playerState?.let(progressForm::apply)
                savedTarget?.applyTo(progressForm)
            }
        }
        val progress = progressForm.normalized()
        val equipment = if (progress.owned && snapshot != null && playerState != null) {
            equipmentService.equipmentFor(snapshot, playerState)
        } else {
            null
        }
        val equippedStats = if (equipment != null && playerState != null) {
            artifactOptimizationService.summarizeCurrentBuild(
                artifacts = equipment.artifacts,
                baseStats = optimizerCombatStatService.resolve(
                    character = playerState,
                    weapon = equipment.weapon,
                    additionalStats = savedTarget?.additionalStats.orEmpty(),
                ),
            )
        } else {
            null
        }
        val equippedWeapon = equipment?.weapon
        val weaponDefinition = equippedWeapon?.let { weaponDataService.find(it.key) }
        val savedWeaponTarget = equippedWeapon?.let { weapon ->
            characterWeaponTargetService.find(principal.id, baseCharacter.key)?.takeIf {
                it.weaponKey == GoodKeyNormalizer.normalize(weapon.key)
            }
        }
        val weaponTargetLevel = equippedWeapon?.let { weapon ->
            WeaponPlanningService.normalizeTargetLevel(
                currentLevel = weapon.level,
                requestedTargetLevel = savedWeaponTarget?.targetLevel ?: weapon.level,
                maxLevel = WeaponPlanningService.maxLevel(weaponDefinition?.rarity ?: 5),
            )
        }
        val weaponPlan = if (
            snapshot != null && equippedWeapon != null && weaponTargetLevel != null
        ) {
            weaponPlanningService.createPlan(equippedWeapon, weaponTargetLevel, snapshot)
        } else {
            null
        }

        model.addAttribute("character", character)
        model.addAttribute("isTraveler", isTraveler)
        model.addAttribute("travelerSelection", travelerSelection)
        model.addAttribute("travelerAppearances", TravelerAppearance.entries)
        model.addAttribute("travelerElements", TravelerElement.entries)
        model.addAttribute("progress", progress)
        model.addAttribute(
            "talentLevels",
            character.combatTalents.associate { talent ->
                talent.key to when (talent.kind) {
                    CharacterTalentKind.NORMAL_ATTACK -> progress.normalTalent
                    CharacterTalentKind.ELEMENTAL_SKILL -> progress.skillTalent
                    CharacterTalentKind.ELEMENTAL_BURST -> progress.burstTalent
                    else -> 1
                }
            },
        )
        model.addAttribute("playerState", playerState)
        model.addAttribute("equipment", equipment)
        model.addAttribute("equippedStats", equippedStats)
        model.addAttribute("additionalStats", savedTarget?.additionalStats.orEmpty())
        model.addAttribute(
            "additionalStatOptions",
            artifactOptimizationService.additionalStatOptions(),
        )
        model.addAttribute("weaponIcon", equipment?.weapon?.let { weaponCatalogService.imageUrl(it.key) })
        model.addAttribute("weaponTargetLevel", weaponTargetLevel)
        model.addAttribute(
            "weaponTargetLevels",
            equipment?.weapon?.let {
                WeaponPlanningService.validTargetLevels(
                    it.level,
                    WeaponPlanningService.maxLevel(weaponDefinition?.rarity ?: 5),
                )
            }.orEmpty(),
        )
        model.addAttribute("weaponPlan", weaponPlan)
        model.addAttribute(
            "artifactIcons",
            artifactCatalogService.imageUrls(equipment?.artifacts.orEmpty()),
        )
        model.addAttribute("inventoryAvailable", snapshot != null)
        model.addAttribute(
            "damageCalculatorAvailable",
            damageCalculatorService.supports(character.key),
        )
        model.addAttribute(
            "damageFormulaV2Available",
            damageFormulaServiceV2.supports(character.key),
        )
        model.addAttribute(
            "materials",
            planningService.calculateBalances(character, progress, snapshot),
        )
        return "character"
    }

    @PostMapping("/characters/{key}")
    fun saveCharacterTarget(
        @PathVariable key: String,
        @ModelAttribute progressForm: CharacterProgressForm,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String {
        val character = catalogService.findCharacter(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Character not found")
        val progress = progressForm.normalized()
        if (character.key == TravelerIdentity.KEY) {
            val selection = ensureTravelerElementSelected(principal.id)
            targetService.saveShared(principal.id, character.key, progress)
            travelerService.saveProgress(principal.id, selection.element, progress)
        } else {
            targetService.save(principal.id, character.key, progress)
        }
        redirectAttributes.addFlashAttribute(
            "successMessage",
            messages.get("character.progress.saved", character.name),
        )
        return "redirect:/characters/${character.key}"
    }

    @PostMapping("/characters/{key}/progress")
    @ResponseBody
    fun saveCharacterProgressAsync(
        @PathVariable key: String,
        @ModelAttribute progressForm: CharacterProgressForm,
        @AuthenticationPrincipal principal: AppUserPrincipal,
    ): Map<String, Int?> {
        val character = catalogService.findCharacter(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Character not found")
        val progress = progressForm.normalized()
        if (character.key == TravelerIdentity.KEY) {
            val selection = ensureTravelerElementSelected(principal.id)
            targetService.saveShared(principal.id, character.key, progress)
            val saved = travelerService.saveProgress(principal.id, selection.element, progress)
            return mapOf(
                "normalTalent" to saved.normalTalent,
                "skillTalent" to saved.skillTalent,
                "burstTalent" to saved.burstTalent,
            )
        }
        val saved = targetService.save(principal.id, character.key, progress)
        return mapOf(
            "normalTalent" to saved.currentNormalTalent,
            "skillTalent" to saved.currentSkillTalent,
            "burstTalent" to saved.currentBurstTalent,
        )
    }

    @PostMapping("/characters/traveler/selection")
    @ResponseBody
    fun saveTravelerSelection(
        @RequestParam(required = false) appearance: String?,
        @RequestParam(required = false) element: String?,
        @AuthenticationPrincipal principal: AppUserPrincipal,
    ): Map<String, Any> {
        val requestedAppearance = TravelerAppearance.fromKey(appearance)
        val requestedElement = TravelerElement.fromKey(element)
        if (appearance != null && requestedAppearance == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown Traveler appearance")
        }
        if (element != null && requestedElement == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown Traveler element")
        }
        if (requestedAppearance == null && requestedElement == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No Traveler selection supplied")
        }

        requestedAppearance?.let { travelerService.selectAppearance(principal.id, it) }
        requestedElement?.let { selectedElement ->
            val importedState = snapshotStore.current(principal.id)?.let { snapshot ->
                planningService.findCharacterState(snapshot, TravelerIdentity.KEY)
            }
            travelerService.selectElement(principal.id, selectedElement, importedState)
        }
        val selection = travelerService.selection(principal.id)
        return mapOf(
            "appearance" to selection.appearance.key,
            "element" to selection.element.key,
            "elementConfigured" to selection.elementConfigured,
        )
    }

    @PostMapping("/characters/{key}/stats")
    fun saveCharacterStats(
        @PathVariable key: String,
        @RequestParam(name = "statBonusKeys", required = false) statKeys: List<String>?,
        @RequestParam(name = "statBonusValues", required = false) statValues: List<String>?,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String {
        val character = catalogService.findCharacter(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Character not found")
        val allowedKeys = artifactOptimizationService.additionalStatOptions()
            .mapTo(mutableSetOf()) { it.key }
        val additionalStats = statKeys.orEmpty().zip(statValues.orEmpty())
            .mapNotNull { (statKey, value) ->
                value.replace(',', '.').toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it != 0.0 && statKey in allowedKeys }
                    ?.coerceIn(-MAX_ADDITIONAL_STAT, MAX_ADDITIONAL_STAT)
                    ?.let { statKey to it }
            }
            .toMap()
        targetService.saveAdditionalStats(principal.id, character.key, additionalStats)
        redirectAttributes.addFlashAttribute(
            "successMessage",
            messages.get("character.stats.saved", character.name),
        )
        return "redirect:/characters/${character.key}"
    }

    @PostMapping("/characters/{key}/weapon-target")
    fun saveWeaponTarget(
        @PathVariable key: String,
        @RequestParam targetLevel: Int,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String {
        val character = catalogService.findCharacter(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Character not found")
        val snapshot = snapshotStore.current(principal.id)
        val state = snapshot?.let { planningService.findCharacterState(it, character.key) }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Character is not imported")
        val weapon = equipmentService.equipmentFor(snapshot, state).weapon
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No weapon equipped")
        val definition = weaponDataService.find(weapon.key)
        characterWeaponTargetService.save(
            userId = principal.id,
            characterKey = character.key,
            weaponKey = weapon.key,
            currentLevel = weapon.level,
            requestedTargetLevel = targetLevel,
            maxLevel = WeaponPlanningService.maxLevel(definition?.rarity ?: 5),
        )
        redirectAttributes.addFlashAttribute(
            "successMessage",
            messages.get("character.weaponTarget.saved", character.name),
        )
        return "redirect:/characters/${character.key}"
    }

    private fun ensureTravelerElementSelected(userId: Long) =
        travelerService.selection(userId).let { selection ->
            if (selection.elementConfigured) {
                selection
            } else {
                val importedState = snapshotStore.current(userId)?.let { snapshot ->
                    planningService.findCharacterState(snapshot, TravelerIdentity.KEY)
                }
                travelerService.selectElement(userId, selection.element, importedState)
            }
        }

    private fun elementFilterOptions(): List<CharacterCatalogFilterOption> =
        ELEMENT_NAMES.map { element ->
            CharacterCatalogFilterOption(
                key = GoodKeyNormalizer.normalize(element),
                label = element,
                imageUrl = fandomImageUrlResolver.elementIconUrl(element),
            )
        }

    private fun weaponFilterOptions(): List<CharacterCatalogFilterOption> =
        WEAPON_TYPES.map { (key, name) ->
            CharacterCatalogFilterOption(
                key = key,
                label = messages.get("weapons.type.$key"),
                imageUrl = fandomImageUrlResolver.weaponTypeIconUrl(name),
            )
        }

    private fun weaponTypeKey(type: String?): String = when (
        GoodKeyNormalizer.normalize(type.orEmpty())
    ) {
        "sword", "onehandedsword" -> "sword"
        "claymore", "twohandedsword" -> "claymore"
        "polearm" -> "polearm"
        "catalyst" -> "catalyst"
        "bow" -> "bow"
        else -> "unknown"
    }

    companion object {
        private const val MAX_ADDITIONAL_STAT = 100_000.0
        private val ELEMENT_NAMES = listOf(
            "Anemo",
            "Geo",
            "Electro",
            "Dendro",
            "Hydro",
            "Pyro",
            "Cryo",
        )
        private val WEAPON_TYPES = listOf(
            "sword" to "Sword",
            "claymore" to "Claymore",
            "polearm" to "Polearm",
            "catalyst" to "Catalyst",
            "bow" to "Bow",
        )
    }
}

data class CharacterCatalogFilterOption(
    val key: String,
    val label: String,
    val imageUrl: String,
)

data class CharacterCatalogCard(
    val character: CharacterDefinition,
    val owned: Boolean,
    val elementKey: String,
    val elementIconUrl: String?,
    val weaponTypeKey: String,
    val searchText: String,
)
