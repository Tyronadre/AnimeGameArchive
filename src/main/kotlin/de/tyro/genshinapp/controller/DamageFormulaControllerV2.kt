package de.tyro.genshinapp.controller

import de.tyro.genshinapp.service.damage.v2.DamageCalculationRequestV2
import de.tyro.genshinapp.service.damage.v2.DamageCalculationV2
import de.tyro.genshinapp.service.damage.v2.DamageFormulaBuildServiceV2
import de.tyro.genshinapp.service.damage.v2.DamageFormulaDocumentV2
import de.tyro.genshinapp.service.damage.v2.DamageFormulaServiceV2
import de.tyro.genshinapp.service.damage.v2.DamageFormulaTeamServiceV2
import de.tyro.genshinapp.service.damage.v2.DamageTeamBuildServiceV2
import de.tyro.genshinapp.security.AppUserPrincipal
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.security.core.annotation.AuthenticationPrincipal

@RestController
@RequestMapping("/api/damage-formulas/v2")
class DamageFormulaControllerV2(
    private val damageFormulaService: DamageFormulaServiceV2,
    private val damageFormulaBuildService: DamageFormulaBuildServiceV2,
    private val damageFormulaTeamService: DamageFormulaTeamServiceV2,
    private val teamBuildService: DamageTeamBuildServiceV2,
) {
    @GetMapping("/{key}")
    fun profile(
        @PathVariable key: String,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        @RequestParam(required = false) team: Set<String>?,
    ): DamageFormulaDocumentV2 {
        val profile = damageFormulaService.profile(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Damage formula profile not found")
        val composed = damageFormulaBuildService.applyCurrentValues(damageFormulaTeamService.compose(profile, team), principal.id)
        return damageFormulaService.document(composed).copy(teamBuilds = teamBuildService.builds(composed, principal.id))
    }

    @PostMapping("/{key}/calculate")
    fun calculate(
        @PathVariable key: String,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        @RequestBody request: DamageCalculationRequestV2,
    ): DamageCalculationV2 {
        val profile = damageFormulaService.profile(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Damage formula profile not found")
        return damageFormulaService.calculate(
            damageFormulaBuildService.applyCurrentValues(damageFormulaTeamService.compose(profile, request.teamKeys), principal.id),
            request,
        )
    }

    @PostMapping("/{key}/team")
    fun team(
        @PathVariable key: String,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        @RequestBody request: DamageCalculationRequestV2,
    ): DamageFormulaDocumentV2 {
        val base = damageFormulaService.profile(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Damage formula profile not found")
        val profile = damageFormulaBuildService.applyCurrentValues(
            damageFormulaTeamService.compose(base, request.teamKeys), principal.id,
        )
        return DamageFormulaDocumentV2(profile, damageFormulaService.calculate(profile, request), teamBuildService.builds(profile, principal.id))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest(exception: IllegalArgumentException) = mapOf("error" to (exception.message ?: "Invalid calculation request"))
}
