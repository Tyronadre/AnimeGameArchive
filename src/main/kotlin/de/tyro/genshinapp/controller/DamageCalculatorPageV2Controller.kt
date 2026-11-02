package de.tyro.genshinapp.controller

import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException

@Controller
class DamageCalculatorPageV2Controller(
    private val characterCatalogService: CharacterCatalogService,
) {
    @GetMapping("/damage-calculator-v2")
    fun calculator(): String = "redirect:/damage-calculator-v2/nefer"

    @GetMapping("/damage-calculator-v2/{key}")
    fun calculator(
        @PathVariable key: String,
        model: Model,
    ): String {
        val character = characterCatalogService.findCharacter(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Character not found")
        model.addAttribute("profileKey", key)
        model.addAttribute("character", character)
        return "damage-calculator-v2"
    }
}
