package de.tyro.genshinapp.controller

import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.damage.v2.DamageFormulaServiceV2
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException

@Controller
class DamageCalculatorPageV2Controller(
    private val damageFormulaService: DamageFormulaServiceV2,
    private val characterCatalogService: CharacterCatalogService,
) {
    @GetMapping("/damage-calculator-v2")
    fun calculator(): String = "redirect:/damage-calculator-v2/nefer"

    @GetMapping("/damage-calculator-v2/{key}")
    fun calculator(
        @PathVariable key: String,
        model: Model,
    ): String {
        val profile = damageFormulaService.profile(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Damage formula profile not found")
        val character = characterCatalogService.findCharacter(profile.characterKey)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Character not found")
        model.addAttribute("profileKey", profile.key)
        model.addAttribute("character", character)
        return "damage-calculator-v2"
    }
}
