package de.tyro.genshinapp.controller

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.damage.DamageCalculatorService
import de.tyro.genshinapp.service.damage.DamageCalculatorBuildService
import de.tyro.genshinapp.service.damage.DamageCalculatorProfile
import de.tyro.genshinapp.service.damage.DamageTeamMemberDefinition
import de.tyro.genshinapp.security.AppUserPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException

@Controller
class DamageCalculatorController(
    private val damageCalculatorService: DamageCalculatorService,
    private val damageCalculatorBuildService: DamageCalculatorBuildService,
    private val characterCatalogService: CharacterCatalogService,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping("/damage-calculator")
    fun calculator(): String = "redirect:/damage-calculator/nefer"

    @GetMapping("/damage-calculator/{key}")
    fun calculator(
        @PathVariable key: String,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val baseProfile = damageCalculatorService.find(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Damage calculator not found")
        val profile = damageCalculatorBuildService.applyCurrentValues(
            withTeamCandidates(baseProfile),
            principal.id,
        )
        val character = characterCatalogService.findCharacter(profile.characterKey)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Character not found")
        val initialValues = damageCalculatorService.defaultValues(profile)
        val initialCalculation = damageCalculatorService.calculate(profile, initialValues)

        model.addAttribute("profile", profile)
        model.addAttribute("profileJson", objectMapper.writeValueAsString(profile))
        model.addAttribute("character", character)
        model.addAttribute("initialCalculation", initialCalculation)
        model.addAttribute("initialResults", initialCalculation.resultsById)
        model.addAttribute(
            "initialEffectValues",
            profile.effectSections
                .flatMap { it.effects }
                .associate { effect -> effect.id to (initialValues.getValue(effect.id) > 0.0) },
        )
        model.addAttribute(
            "currentStatsLoaded",
            profile.inputSections.flatMap { it.inputs }.any { it.currentValueLoaded },
        )
        return "damage-calculator"
    }

    private fun withTeamCandidates(profile: DamageCalculatorProfile): DamageCalculatorProfile {
        val configuredKeys = profile.teamMembers.mapTo(mutableSetOf()) { it.key }
        val catalogCandidates = characterCatalogService.getCharacters()
            .sortedBy { it.name }
            .mapNotNull { character ->
                if (!configuredKeys.add(character.key)) return@mapNotNull null
                DamageTeamMemberDefinition(
                    key = character.key,
                    name = character.name,
                    element = character.element ?: "—",
                    moonsignContribution = if (
                        character.passiveTalents.any { talent ->
                            talent.description.contains("party's Moonsign", ignoreCase = true)
                        }
                    ) {
                        1
                    } else {
                        0
                    },
                )
            }
        return profile.copy(teamMembers = profile.teamMembers + catalogCandidates)
    }
}
