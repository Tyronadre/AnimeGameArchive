package de.tyro.genshinapp.controller

import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.CharacterTargetService
import de.tyro.genshinapp.service.MissingMaterialsPageService
import de.tyro.genshinapp.service.PlayerPlanningService
import de.tyro.genshinapp.service.PlayerSnapshotStore
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

@Controller
@RequestMapping("/materials")
class MissingMaterialsController(
    private val snapshotStore: PlayerSnapshotStore,
    private val planningService: PlayerPlanningService,
    private val characterTargetService: CharacterTargetService,
    private val pageService: MissingMaterialsPageService,
) {
    @GetMapping
    fun missingMaterials(
        @RequestParam(required = false) materialId: Int?,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val snapshot = snapshotStore.current(principal.id)
        val plan = snapshot?.let {
            planningService.createPlan(
                it,
                characterTargetService.findAll(principal.id),
                principal.id,
            )
        }
        val materialsPage = plan?.let(pageService::create)
        val selectedMaterial = materialId?.let { materialsPage?.allItemsById?.get(it) }

        model.addAttribute("plan", plan)
        model.addAttribute("materialsPage", materialsPage)
        model.addAttribute("selectedMaterial", selectedMaterial)
        model.addAttribute(
            "characterNeeds",
            selectedMaterial?.let { plan?.characterNeeds(it.id) }.orEmpty(),
        )
        return "missing-materials"
    }

    @GetMapping("/popup")
    fun materialPopup(
        @RequestParam materialId: Int,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val snapshot = snapshotStore.current(principal.id)
        val plan = snapshot?.let {
            planningService.createPlan(
                it,
                characterTargetService.findAll(principal.id),
                principal.id,
            )
        }
        val selectedMaterial = plan
            ?.let(pageService::create)
            ?.allItemsById
            ?.get(materialId)

        model.addAttribute("selectedMaterial", selectedMaterial)
        model.addAttribute(
            "characterNeeds",
            selectedMaterial?.let { plan.characterNeeds(it.id) }.orEmpty(),
        )
        return "missing-material-dialog :: materialDialog"
    }

    @GetMapping("/api/revision")
    @ResponseBody
    fun revision(
        @AuthenticationPrincipal principal: AppUserPrincipal,
    ): Map<String, Long> = mapOf(
        "revision" to (snapshotStore.current(principal.id)?.revision ?: 0L),
    )

    @GetMapping("/api/state")
    @ResponseBody
    fun liveState(
        @AuthenticationPrincipal principal: AppUserPrincipal,
    ): MissingMaterialsLiveState {
        val snapshot = snapshotStore.current(principal.id)
            ?: return MissingMaterialsLiveState(revision = 0L, materials = emptyMap())
        val plan = planningService.createPlan(
            snapshot,
            characterTargetService.findAll(principal.id),
            principal.id,
        )
        val materials = pageService.create(plan).allItemsById.mapValues { (_, item) ->
            MissingMaterialLiveValue(
                owned = item.owned,
                required = item.required,
                craftable = item.craftable,
                needed = item.needed,
                freeToUse = item.freeToUse,
            )
        }
        return MissingMaterialsLiveState(
            revision = snapshot.revision,
            summary = MissingMaterialsLiveSummary(
                matchedCharacters = plan.matchedCharacters,
                characterCount = snapshot.characters.size,
                inventoryEntries = snapshot.exportedInventoryKeys,
                missingMaterialKinds = plan.missingMaterialKinds,
            ),
            materials = materials,
        )
    }
}

data class MissingMaterialsLiveState(
    val revision: Long,
    val summary: MissingMaterialsLiveSummary? = null,
    val materials: Map<Int, MissingMaterialLiveValue>,
)

data class MissingMaterialsLiveSummary(
    val matchedCharacters: Int,
    val characterCount: Int,
    val inventoryEntries: Int,
    val missingMaterialKinds: Int,
)

data class MissingMaterialLiveValue(
    val owned: Long,
    val required: Long,
    val craftable: Long,
    val needed: Long,
    val freeToUse: Long,
)
