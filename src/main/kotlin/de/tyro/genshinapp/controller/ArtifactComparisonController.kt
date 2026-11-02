package de.tyro.genshinapp.controller

import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.damage.v2.comparison.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/damage-formulas/v2/{key}/artifact-comparisons")
class ArtifactComparisonController(private val service: ArtifactComparisonService) {
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(@PathVariable key: String, @AuthenticationPrincipal principal: AppUserPrincipal,
              @RequestBody request: ArtifactComparisonRequest) = service.start(key, principal.id, request)

    @GetMapping("/{runId}")
    fun status(@PathVariable key: String, @PathVariable runId: String,
               @AuthenticationPrincipal principal: AppUserPrincipal,
               @RequestParam(required = false) slot: String?, @RequestParam(defaultValue = "current") sort: String,
               @RequestParam(defaultValue = "0") offset: Int, @RequestParam(defaultValue = "50") limit: Int) =
        service.status(key, principal.id, runId, slot, sort, offset, limit)

    @DeleteMapping("/{runId}")
    fun cancel(@PathVariable key: String, @PathVariable runId: String, @AuthenticationPrincipal principal: AppUserPrincipal) =
        service.cancel(key, principal.id, runId)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(error: IllegalArgumentException) = mapOf("error" to (error.message ?: "comparison.invalidInputs"))

    @ExceptionHandler(ResponseStatusException::class)
    fun statusError(error: ResponseStatusException) = org.springframework.http.ResponseEntity
        .status(error.statusCode).body(mapOf("error" to error.reason))
}
