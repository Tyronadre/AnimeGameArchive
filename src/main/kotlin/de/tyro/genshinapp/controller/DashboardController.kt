package de.tyro.genshinapp.controller

import de.tyro.genshinapp.configuration.LocalizedMessages
import de.tyro.genshinapp.model.CharacterProgressForm
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.CharacterCatalogService
import de.tyro.genshinapp.service.CharacterTargetService
import de.tyro.genshinapp.service.DashboardGoalSelection
import de.tyro.genshinapp.service.DashboardGoalService
import de.tyro.genshinapp.service.DashboardGoalType
import de.tyro.genshinapp.service.FarmingDashboardService
import de.tyro.genshinapp.service.PlayerPlanningService
import de.tyro.genshinapp.service.PlayerSnapshotStore
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class DashboardController(
    private val snapshotStore: PlayerSnapshotStore,
    private val catalogService: CharacterCatalogService,
    private val targetService: CharacterTargetService,
    private val goalService: DashboardGoalService,
    private val dashboardService: FarmingDashboardService,
    private val planningService: PlayerPlanningService,
    private val messages: LocalizedMessages,
) {
    @GetMapping("/")
    fun dashboard(
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val snapshot = snapshotStore.current(principal.id)
        val selections = goalService.findAll(principal.id)
        val options = goalOptions(principal.id, snapshot, selections)
        model.addAttribute("snapshot", snapshot)
        model.addAttribute("goalOptions", options)
        model.addAttribute("selectedGoalCount", selections.size)
        model.addAttribute(
            "dashboard",
            snapshot?.let { dashboardService.create(principal.id, it, selections) },
        )
        return "dashboard"
    }

    @PostMapping("/goals")
    fun saveGoals(
        @RequestParam(required = false) characterGoals: List<String>?,
        @RequestParam(required = false) artifactGoals: List<String>?,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String {
        val snapshot = snapshotStore.current(principal.id)
        if (snapshot == null) {
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                messages.get("dashboard.goals.noInventory"),
            )
            return "redirect:/"
        }
        val availableOptions = goalOptions(principal.id, snapshot, emptySet())
        val characterKeys = availableOptions.filter(DashboardCharacterGoalOption::owned)
            .mapTo(mutableSetOf()) { GoodKeyNormalizer.normalize(it.key) }
        val artifactKeys = availableOptions.filter(DashboardCharacterGoalOption::canOptimize)
            .mapTo(mutableSetOf()) { GoodKeyNormalizer.normalize(it.key) }
        val selections = buildSet {
            characterGoals.orEmpty()
                .map(GoodKeyNormalizer::normalize)
                .filter { it in characterKeys }
                .forEach { add(DashboardGoalSelection(it, DashboardGoalType.CHARACTER)) }
            artifactGoals.orEmpty()
                .map(GoodKeyNormalizer::normalize)
                .filter { it in artifactKeys }
                .forEach { add(DashboardGoalSelection(it, DashboardGoalType.ARTIFACTS)) }
        }
        goalService.replace(principal.id, selections)
        redirectAttributes.addFlashAttribute(
            "successMessage",
            messages.get("dashboard.goals.saved", selections.size),
        )
        return "redirect:/"
    }

    private fun goalOptions(
        userId: Long,
        snapshot: PlayerSnapshot?,
        selections: Set<DashboardGoalSelection>,
    ): List<DashboardCharacterGoalOption> {
        val equippedArtifactCounts = snapshot?.artifacts.orEmpty()
            .mapNotNull(PlayerArtifactLocation::from)
            .groupingBy(PlayerArtifactLocation::characterKey)
            .eachCount()
        val targets = targetService.findAll(userId)

        return catalogService.getCharacters().mapNotNull { character ->
            val normalizedKey = GoodKeyNormalizer.normalize(character.key)
            val state = snapshot?.let {
                planningService.findCharacterState(it, character.key)
            }
            val target = targets[normalizedKey]
            val form = CharacterProgressForm()
            state?.let(form::apply)
            target?.applyTo(form)
            val progress = form.normalized()
            if (!progress.owned) return@mapNotNull null
            val stateArtifactKey = GoodKeyNormalizer.normalize(state?.key.orEmpty())
            DashboardCharacterGoalOption(
                key = character.key,
                name = character.name,
                iconUrl = character.iconImageUrl,
                owned = true,
                currentLevel = progress.level,
                targetLevel = progress.targetLevel,
                equippedArtifacts = equippedArtifactCounts[stateArtifactKey] ?: 0,
                canOptimize = state != null &&
                    normalizedKey !in TRAVELER_KEYS &&
                    (equippedArtifactCounts[stateArtifactKey] ?: 0) > 0,
                characterSelected = DashboardGoalSelection(
                    normalizedKey,
                    DashboardGoalType.CHARACTER,
                ) in selections,
                artifactsSelected = DashboardGoalSelection(
                    normalizedKey,
                    DashboardGoalType.ARTIFACTS,
                ) in selections,
            )
        }.sortedWith(
            compareByDescending<DashboardCharacterGoalOption> {
                it.characterSelected || it.artifactsSelected
            }.thenBy(DashboardCharacterGoalOption::name),
        )
    }

    companion object {
        private val TRAVELER_KEYS = setOf("aether", "lumine")
    }

    private data class PlayerArtifactLocation(
        val characterKey: String,
    ) {
        companion object {
            fun from(artifact: de.tyro.genshinapp.model.PlayerArtifact): PlayerArtifactLocation? =
                artifact.location
                    ?.takeIf(String::isNotBlank)
                    ?.let(GoodKeyNormalizer::normalize)
                    ?.let(::PlayerArtifactLocation)
        }
    }
}

data class DashboardCharacterGoalOption(
    val key: String,
    val name: String,
    val iconUrl: String?,
    val owned: Boolean,
    val currentLevel: Int,
    val targetLevel: Int,
    val equippedArtifacts: Int,
    val canOptimize: Boolean,
    val characterSelected: Boolean,
    val artifactsSelected: Boolean,
)
