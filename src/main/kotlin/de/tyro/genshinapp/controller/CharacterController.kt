package de.tyro.genshinapp.controller

import de.tyro.genshinapp.configuration.LocalizedMessages
import de.tyro.genshinapp.model.CharacterProgressForm
import de.tyro.genshinapp.model.CharacterTalentKind
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.ArtifactCatalogService
import de.tyro.genshinapp.service.ArtifactOptimizationService
import de.tyro.genshinapp.service.CharacterCatalogService
import de.tyro.genshinapp.service.CharacterTargetService
import de.tyro.genshinapp.service.CharacterWeaponTargetService
import de.tyro.genshinapp.service.PlayerEquipmentService
import de.tyro.genshinapp.service.PlayerPlanningService
import de.tyro.genshinapp.service.PlayerSnapshotStore
import de.tyro.genshinapp.service.OptimizerCombatStatService
import de.tyro.genshinapp.service.WeaponCatalogService
import de.tyro.genshinapp.service.WeaponDataService
import de.tyro.genshinapp.service.WeaponPlanningService
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
    private val messages: LocalizedMessages,
) {
    @GetMapping("/characters")
    fun characters(
        @RequestParam(required = false, defaultValue = "") query: String,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val normalizedQuery = query.trim()
        val characters = catalogService.getCharacters()
            .filter {
                normalizedQuery.isBlank() ||
                    it.name.contains(normalizedQuery, ignoreCase = true) ||
                    it.element?.contains(normalizedQuery, ignoreCase = true) == true ||
                    it.region?.contains(normalizedQuery, ignoreCase = true) == true
            }
        val snapshot = snapshotStore.current(principal.id)
        val ownershipOverrides = targetService.ownershipOverrides(principal.id)
        val ownershipByCharacter = characters.associate { character ->
            val normalizedKey = GoodKeyNormalizer.normalize(character.key)
            val importedOwnership = snapshot?.let {
                planningService.findCharacterState(it, character.key) != null
            } ?: false
            character.key to (ownershipOverrides[normalizedKey] ?: importedOwnership)
        }

        model.addAttribute("characters", characters)
        model.addAttribute("ownershipByCharacter", ownershipByCharacter)
        model.addAttribute("query", normalizedQuery)
        model.addAttribute("totalCharacters", catalogService.getCharacters().size)
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
        val character = catalogService.findCharacter(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Character not found")
        val snapshot = snapshotStore.current(principal.id)
        val playerState = snapshot?.let {
            planningService.findCharacterState(it, character.key)
        }
        if (requestParameters.isEmpty() && playerState != null) {
            progressForm.apply(playerState)
        }
        val savedTarget = targetService.find(principal.id, character.key)
        if (requestParameters.isEmpty()) {
            savedTarget?.applyTo(progressForm)
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
            characterWeaponTargetService.find(principal.id, character.key)?.takeIf {
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
        targetService.save(principal.id, character.key, progressForm.normalized())
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
        val saved = targetService.save(principal.id, character.key, progressForm.normalized())
        return mapOf(
            "normalTalent" to saved.currentNormalTalent,
            "skillTalent" to saved.currentSkillTalent,
            "burstTalent" to saved.currentBurstTalent,
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

    companion object {
        private const val MAX_ADDITIONAL_STAT = 100_000.0
    }
}
