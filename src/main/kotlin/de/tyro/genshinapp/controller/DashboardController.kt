package de.tyro.genshinapp.controller

import de.tyro.genshinapp.configuration.LocalizedMessages
import de.tyro.genshinapp.model.CharacterProgressForm
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.TravelerIdentity
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.CharacterCatalogService
import de.tyro.genshinapp.service.CharacterTargetService
import de.tyro.genshinapp.service.DashboardGoalSelection
import de.tyro.genshinapp.service.DashboardGoalService
import de.tyro.genshinapp.service.DashboardGoalType
import de.tyro.genshinapp.service.FarmingDashboardService
import de.tyro.genshinapp.service.FarmingRecommendation
import de.tyro.genshinapp.service.PlayerPlanningService
import de.tyro.genshinapp.service.PlayerSnapshotStore
import de.tyro.genshinapp.service.SnapshotActivityEvent
import de.tyro.genshinapp.service.SnapshotActivityService
import de.tyro.genshinapp.service.SnapshotActivityType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.Duration
import java.time.Instant
import java.util.Locale

@Controller
class DashboardController(
    private val snapshotStore: PlayerSnapshotStore,
    private val catalogService: CharacterCatalogService,
    private val targetService: CharacterTargetService,
    private val goalService: DashboardGoalService,
    private val dashboardService: FarmingDashboardService,
    private val planningService: PlayerPlanningService,
    private val activityService: SnapshotActivityService,
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
        val effectiveSelections = selections.ifEmpty {
            automaticSelections(options)
        }
        model.addAttribute("snapshot", snapshot)
        model.addAttribute("goalOptions", options)
        model.addAttribute("goalElementFilters", goalElementFilters(options))
        model.addAttribute("selectedGoalCount", selections.size)
        model.addAttribute("dashboardAutomatic", selections.isEmpty())
        model.addAttribute("recentActivity", recentActivityViews(principal.id))
        model.addAttribute(
            "dashboard",
            snapshot?.let {
                dashboardService.create(
                    principal.id,
                    it,
                    effectiveSelections,
                    automaticPlan = selections.isEmpty(),
                )
            },
        )
        return "dashboard"
    }

    @GetMapping("/api/dashboard/recent-activity")
    @ResponseBody
    fun recentActivity(
        @AuthenticationPrincipal principal: AppUserPrincipal,
    ): DashboardActivityResponse = DashboardActivityResponse(
        revision = snapshotStore.current(principal.id)?.revision ?: 0,
        activities = recentActivityViews(principal.id),
    )

    @GetMapping("/api/dashboard/plan")
    @ResponseBody
    fun dashboardPlan(
        @AuthenticationPrincipal principal: AppUserPrincipal,
    ): DashboardPlanResponse {
        val snapshot = snapshotStore.current(principal.id)
        if (snapshot == null) {
            return DashboardPlanResponse.empty()
        }
        val selections = goalService.findAll(principal.id)
        val options = goalOptions(principal.id, snapshot, selections)
        val dashboard = dashboardService.create(
            principal.id,
            snapshot,
            selections.ifEmpty { automaticSelections(options) },
            automaticPlan = selections.isEmpty(),
        )
        return DashboardPlanResponse(
            revision = snapshot.revision,
            weekly = recommendationGroup(dashboard.weeklyRecommendations, WEEKLY_VISIBLE_COUNT),
            resin = recommendationGroup(dashboard.resinRecommendations, TASK_VISIBLE_COUNT),
            free = recommendationGroup(dashboard.freeRecommendations, TASK_VISIBLE_COUNT),
        )
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
            val normalizedKey = TravelerIdentity.canonicalCharacterKey(character.key)
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
            val elementKey = elementKey(character.element)
            DashboardCharacterGoalOption(
                key = character.key,
                name = character.name,
                iconUrl = character.iconImageUrl,
                elementKey = elementKey,
                searchText = listOf(
                    character.name,
                    character.key,
                    character.element.orEmpty(),
                ).filter(String::isNotBlank)
                    .joinToString(" ")
                    .lowercase(Locale.ROOT),
                owned = true,
                currentLevel = progress.level,
                targetLevel = progress.targetLevel,
                currentTalentTotal = progress.normalTalent +
                    progress.skillTalent + progress.burstTalent,
                targetTalentTotal = progress.targetNormalTalent +
                    progress.targetSkillTalent + progress.targetBurstTalent,
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

    private fun goalElementFilters(
        options: List<DashboardCharacterGoalOption>,
    ): List<DashboardElementFilterView> {
        val availableElements = options
            .mapTo(mutableSetOf()) { it.elementKey }
            .filter(String::isNotBlank)
            .toSet()
        return ELEMENT_ORDER.filter(availableElements::contains).map { element ->
            DashboardElementFilterView(
                key = element,
                label = messages.get("element.$element"),
            )
        }
    }

    private fun elementKey(element: String?): String {
        val normalized = element?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return normalized.takeUnless { it in IGNORED_ELEMENT_KEYS }.orEmpty()
    }

    private fun automaticSelections(
        options: List<DashboardCharacterGoalOption>,
    ): Set<DashboardGoalSelection> {
        val candidates = options.filter {
            it.currentLevel < it.targetLevel ||
                it.currentTalentTotal < it.targetTalentTotal
        }.sortedWith(
            compareByDescending<DashboardCharacterGoalOption> { it.currentLevel }
                .thenByDescending { it.currentTalentTotal },
        )

        return buildSet {
            candidates.take(AUTOMATIC_CHARACTER_COUNT).forEach { option ->
                add(
                    DashboardGoalSelection(
                        GoodKeyNormalizer.normalize(option.key),
                        DashboardGoalType.CHARACTER,
                    ),
                )
            }
            options.asSequence()
                .filter(DashboardCharacterGoalOption::canOptimize)
                .sortedWith(
                    compareByDescending<DashboardCharacterGoalOption> { it.currentLevel }
                        .thenByDescending { it.currentTalentTotal },
                )
                .take(AUTOMATIC_ARTIFACT_COUNT)
                .forEach { option ->
                    add(
                        DashboardGoalSelection(
                            GoodKeyNormalizer.normalize(option.key),
                            DashboardGoalType.ARTIFACTS,
                        ),
                    )
                }
        }
    }

    private fun activityView(event: SnapshotActivityEvent): DashboardActivityView {
        val title: String
        val detail: String
        when (event.type) {
            SnapshotActivityType.MATERIAL_GAIN -> {
                title = messages.get("dashboard.recent.materialGain", event.name)
                detail = messages.get(
                    "dashboard.recent.materialGain.detail",
                    event.amount ?: 0,
                    event.total ?: 0,
                )
            }
            SnapshotActivityType.MATERIAL_SPEND -> {
                title = messages.get("dashboard.recent.materialSpend", event.name)
                detail = messages.get(
                    "dashboard.recent.materialSpend.detail",
                    event.amount ?: 0,
                    event.total ?: 0,
                )
            }
            SnapshotActivityType.ARTIFACT_LEVEL -> {
                title = messages.get("dashboard.recent.artifactLevel", event.name)
                detail = messages.get(
                    "dashboard.recent.level.detail",
                    event.detailName.orEmpty(),
                    event.previousLevel ?: 0,
                    event.currentLevel ?: 0,
                )
            }
            SnapshotActivityType.ARTIFACT_ADDED -> {
                title = messages.get("dashboard.recent.artifactAdded", event.name)
                detail = messages.get(
                    "dashboard.recent.newLevel.detail",
                    event.detailName.orEmpty(),
                    event.currentLevel ?: 0,
                )
            }
            SnapshotActivityType.ARTIFACTS_REMOVED -> {
                title = messages.get("dashboard.recent.artifactsRemoved", event.amount ?: 0)
                detail = messages.get(
                    "dashboard.recent.artifactsRemoved.detail",
                    event.total ?: 0,
                )
            }
            SnapshotActivityType.WEAPON_LEVEL -> {
                title = messages.get("dashboard.recent.weaponLevel", event.name)
                detail = messages.get(
                    "dashboard.recent.weaponLevel.detail",
                    event.previousLevel ?: 0,
                    event.currentLevel ?: 0,
                )
            }
            SnapshotActivityType.WEAPON_ADDED -> {
                title = messages.get("dashboard.recent.weaponAdded", event.name)
                detail = messages.get(
                    "dashboard.recent.weaponAdded.detail",
                    event.currentLevel ?: 0,
                )
            }
            SnapshotActivityType.WEAPON_REMOVED -> {
                title = messages.get("dashboard.recent.weaponRemoved", event.name)
                detail = messages.get(
                    "dashboard.recent.weaponRemoved.detail",
                    event.previousLevel ?: 0,
                )
            }
            SnapshotActivityType.CHARACTER_LEVEL -> {
                title = messages.get("dashboard.recent.characterLevel", event.name)
                detail = messages.get(
                    "dashboard.recent.characterLevel.detail",
                    event.previousLevel ?: 0,
                    event.currentLevel ?: 0,
                )
            }
        }
        return DashboardActivityView(
            icon = event.type.icon,
            tone = event.type.tone,
            title = title,
            detail = detail,
            occurredAt = event.occurredAt.toString(),
            age = activityAge(event.occurredAt),
        )
    }

    private fun recentActivityViews(userId: Long): List<DashboardActivityView> =
        activityService.recent(userId).map(::activityView)

    private fun recommendationGroup(
        recommendations: List<FarmingRecommendation>,
        visibleLimit: Int,
    ): DashboardRecommendationGroup = DashboardRecommendationGroup(
        visibleLimit = visibleLimit,
        moreText = recommendations.size
            .takeIf { it > visibleLimit }
            ?.let { messages.get("dashboard.more", it - visibleLimit) },
        items = recommendations.map(::recommendationView),
    )

    private fun recommendationView(
        recommendation: FarmingRecommendation,
    ): DashboardRecommendationView = DashboardRecommendationView(
        key = recommendation.key,
        title = recommendation.title,
        href = recommendation.href,
        imageUrl = recommendation.imageUrl,
        activityIcon = recommendation.activity.icon,
        activityLabel = messages.get(recommendation.activity.messageKey),
        helpsText = messages.get("dashboard.card.helps", recommendation.characters.size),
        progressText = recommendation.requiredTotal
            .takeIf { it > 0 }
            ?.let {
                messages.get(
                    "dashboard.card.materialProgress",
                    recommendation.coveredTotal,
                    recommendation.requiredTotal,
                )
            },
        resinCost = recommendation.activity.resinCost,
        freeCostText = messages.get("dashboard.free.cost"),
        recordRunLabel = messages.get("dashboard.resin.recordRun", recommendation.title),
    )

    private fun activityAge(occurredAt: Instant): String {
        val age = Duration.between(occurredAt, Instant.now()).coerceAtLeast(Duration.ZERO)
        return when {
            age.toMinutes() < 1 -> messages.get("dashboard.recent.justNow")
            age.toHours() < 1 -> messages.get("dashboard.recent.minutesAgo", age.toMinutes())
            age.toDays() < 1 -> messages.get("dashboard.recent.hoursAgo", age.toHours())
            else -> messages.get("dashboard.recent.daysAgo", age.toDays())
        }
    }

    companion object {
        private val TRAVELER_KEYS = setOf("aether", "lumine")
        private val ELEMENT_ORDER = listOf(
            "anemo",
            "geo",
            "electro",
            "dendro",
            "hydro",
            "pyro",
            "cryo",
        )
        private val IGNORED_ELEMENT_KEYS = setOf("none", "unknown")
        private const val AUTOMATIC_CHARACTER_COUNT = 10
        private const val AUTOMATIC_ARTIFACT_COUNT = 1
        private const val WEEKLY_VISIBLE_COUNT = 3
        private const val TASK_VISIBLE_COUNT = 5
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
    val elementKey: String,
    val searchText: String,
    val owned: Boolean,
    val currentLevel: Int,
    val targetLevel: Int,
    val currentTalentTotal: Int,
    val targetTalentTotal: Int,
    val equippedArtifacts: Int,
    val canOptimize: Boolean,
    val characterSelected: Boolean,
    val artifactsSelected: Boolean,
)

data class DashboardElementFilterView(
    val key: String,
    val label: String,
)

data class DashboardActivityView(
    val icon: String,
    val tone: String,
    val title: String,
    val detail: String,
    val occurredAt: String,
    val age: String,
)

data class DashboardActivityResponse(
    val revision: Long,
    val activities: List<DashboardActivityView>,
)

data class DashboardPlanResponse(
    val revision: Long,
    val weekly: DashboardRecommendationGroup,
    val resin: DashboardRecommendationGroup,
    val free: DashboardRecommendationGroup,
) {
    companion object {
        fun empty(): DashboardPlanResponse = DashboardPlanResponse(
            revision = 0,
            weekly = DashboardRecommendationGroup.empty(3),
            resin = DashboardRecommendationGroup.empty(5),
            free = DashboardRecommendationGroup.empty(5),
        )
    }
}

data class DashboardRecommendationGroup(
    val visibleLimit: Int,
    val moreText: String?,
    val items: List<DashboardRecommendationView>,
) {
    companion object {
        fun empty(visibleLimit: Int): DashboardRecommendationGroup =
            DashboardRecommendationGroup(visibleLimit, null, emptyList())
    }
}

data class DashboardRecommendationView(
    val key: String,
    val title: String,
    val href: String,
    val imageUrl: String?,
    val activityIcon: String,
    val activityLabel: String,
    val helpsText: String,
    val progressText: String?,
    val resinCost: Int,
    val freeCostText: String,
    val recordRunLabel: String,
)
