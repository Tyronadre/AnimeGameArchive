package de.tyro.genshinapp.controller

import de.tyro.genshinapp.configuration.LocalizedMessages
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerWeapon
import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.ArtifactCatalogService
import de.tyro.genshinapp.service.ArtifactMutationRequest
import de.tyro.genshinapp.service.ArtifactOptimizationProfile
import de.tyro.genshinapp.service.ArtifactOptimizationService
import de.tyro.genshinapp.service.ArtifactOptimizerCustomProfileService
import de.tyro.genshinapp.service.ArtifactOptimizerProfileService
import de.tyro.genshinapp.service.ArtifactOptimizerSharingService
import de.tyro.genshinapp.service.ArtifactSetSelection
import de.tyro.genshinapp.service.ArtifactSetSelectionMode
import de.tyro.genshinapp.service.ArtifactSetTarget
import de.tyro.genshinapp.service.ArtifactStatInput
import de.tyro.genshinapp.service.CharacterCatalogService
import de.tyro.genshinapp.service.CharacterTargetService
import de.tyro.genshinapp.service.GoodImportException
import de.tyro.genshinapp.service.GoodImportService
import de.tyro.genshinapp.service.MaterialCraftingService
import de.tyro.genshinapp.service.OptimizerCombatStatService
import de.tyro.genshinapp.service.PlayerPlanningService
import de.tyro.genshinapp.service.PlayerArtifactManagementService
import de.tyro.genshinapp.service.PlayerSnapshotStore
import de.tyro.genshinapp.service.WeaponCatalogService
import de.tyro.genshinapp.service.WeaponDataService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/inventory")
class PlayerInventoryController(
    private val snapshotStore: PlayerSnapshotStore,
    private val planningService: PlayerPlanningService,
    private val catalogService: CharacterCatalogService,
    private val artifactCatalogService: ArtifactCatalogService,
    private val artifactManagementService: PlayerArtifactManagementService,
    private val weaponCatalogService: WeaponCatalogService,
    private val materialCraftingService: MaterialCraftingService,
    private val artifactOptimizationService: ArtifactOptimizationService,
    private val artifactOptimizerCustomProfileService: ArtifactOptimizerCustomProfileService,
    private val artifactOptimizerProfileService: ArtifactOptimizerProfileService,
    private val artifactOptimizerSharingService: ArtifactOptimizerSharingService,
    private val characterTargetService: CharacterTargetService,
    private val weaponDataService: WeaponDataService,
    private val optimizerCombatStatService: OptimizerCombatStatService,
    private val messages: LocalizedMessages,
) {
    @GetMapping
    fun inventory(): String = "redirect:/inventory/items"

    @GetMapping("/items")
    fun items(
        @RequestParam(defaultValue = "") query: String,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val snapshot = snapshotStore.current(principal.id)
        val normalizedQuery = query.trim()
        val catalogMaterials = catalogService.getMaterials()
            .filter { it.id > 0 }
            .associateBy { GoodKeyNormalizer.normalize(it.name) }
        val keys = buildSet {
            snapshot?.inventory?.keys?.let(::addAll)
            addAll(catalogMaterials.keys)
            addAll(EXPERIENCE_ITEM_KEYS)
        }
        val items = keys.map { key ->
            val catalogMaterial = catalogMaterials[key]
            val craftingInfo = catalogMaterial?.let {
                materialCraftingService.infoFor(it.id)
            }
            val availability = catalogMaterial?.let {
                materialCraftingService.inventoryAvailability(
                    it.id,
                    snapshot?.inventory.orEmpty(),
                )
            }
            val name = catalogMaterial?.name
                ?: snapshot?.inventoryNames?.get(key)?.let(GoodKeyNormalizer::humanize)
                ?: GoodKeyNormalizer.humanize(key)
            InventoryItemRow(
                key = key,
                name = name,
                amount = snapshot?.inventory?.getOrDefault(key, 0L) ?: 0L,
                imageUrl = catalogMaterial?.let { catalogService.materialImageUrl(it.id) },
                buildMaterial = catalogMaterial != null || key in EXPERIENCE_ITEM_KEYS,
                categoryMessageKey = craftingInfo?.category?.messageKey,
                craftableAmount = availability?.craftable ?: 0L,
                availableAmount = availability?.available
                    ?: snapshot?.inventory?.getOrDefault(key, 0L)
                    ?: 0L,
            )
        }.filter {
            normalizedQuery.isBlank() ||
                it.name.contains(normalizedQuery, ignoreCase = true) ||
                it.key.contains(normalizedQuery, ignoreCase = true)
        }.sortedWith(
            compareBy<InventoryItemRow> { if (it.buildMaterial) 0 else 1 }.thenBy { it.name },
        )

        model.addAttribute("snapshot", snapshot)
        model.addAttribute("items", items)
        model.addAttribute("query", normalizedQuery)
        model.addAttribute("snapshotFile", snapshotStore.filePath(principal.id).toString())
        return "inventory-items"
    }

    @PostMapping("/items/{key}")
    fun updateItem(
        @PathVariable key: String,
        @RequestParam amount: Long,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String {
        return try {
            snapshotStore.updateInventoryAmount(principal.id, key, amount)
            redirectAttributes.addFlashAttribute(
                "successMessage",
                messages.get("inventory.item.saved"),
            )
            "redirect:/inventory/items"
        } catch (_: IllegalArgumentException) {
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                messages.get("inventory.item.invalid"),
            )
            "redirect:/inventory/items"
        } catch (_: IllegalStateException) {
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                messages.get("inventory.import.first"),
            )
            "redirect:/inventory/items"
        }
    }

    @GetMapping("/missing")
    fun missing(
        @RequestParam(required = false) materialId: Int?,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val snapshot = snapshotStore.current(principal.id)
        val plan = snapshot?.let(planningService::createPlan)
        val selectedMaterial = materialId?.let { id ->
            plan?.missingMaterials?.find { it.id == id }
        }
        model.addAttribute("plan", plan)
        model.addAttribute("selectedMaterial", selectedMaterial)
        model.addAttribute(
            "characterNeeds",
            selectedMaterial?.let { plan?.characterNeeds(it.id).orEmpty() }.orEmpty(),
        )
        model.addAttribute("snapshotFile", snapshotStore.filePath(principal.id).toString())
        return "inventory"
    }

    @GetMapping("/artifacts")
    fun artifacts(
        @RequestParam(defaultValue = "") query: String,
        @RequestParam(defaultValue = "all") slot: String,
        @RequestParam(required = false) character: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val snapshot = snapshotStore.current(principal.id)
        val normalizedQuery = query.trim()
        val filtered = snapshot?.artifacts.orEmpty()
            .mapIndexed(::InventoryArtifactRow)
            .filter { slot == "all" || it.artifact.slotKey.equals(slot, ignoreCase = true) }
            .filter { row ->
                val artifact = row.artifact
                normalizedQuery.isBlank() ||
                    artifact.setName.contains(normalizedQuery, ignoreCase = true) ||
                    artifact.location?.contains(normalizedQuery, ignoreCase = true) == true ||
                    artifact.mainStatName.contains(normalizedQuery, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<InventoryArtifactRow> { it.artifact.rarity }
                    .thenByDescending { it.artifact.level },
            )
        val artifactPage = paginate(filtered, page)
        val displayedArtifacts = artifactPage.items.map(InventoryArtifactRow::artifact)

        model.addAttribute("snapshot", snapshot)
        model.addAttribute("artifacts", artifactPage.items)
        model.addAttribute("artifactIcons", artifactCatalogService.imageUrls(displayedArtifacts))
        model.addAttribute("page", artifactPage.page)
        model.addAttribute("totalPages", artifactPage.totalPages)
        model.addAttribute("totalItems", artifactPage.totalItems)
        model.addAttribute("query", normalizedQuery)
        model.addAttribute("selectedSlot", slot)
        model.addAttribute("selectedArtifactCharacter", character)
        model.addAttribute(
            "artifactCharacters",
            snapshot?.characters.orEmpty()
                .map { state ->
                    ArtifactAssignmentCharacter(
                        key = state.key,
                        name = catalogService.findCharacter(state.key.lowercase())?.name
                            ?: GoodKeyNormalizer.humanize(state.key),
                    )
                }
                .sortedBy(ArtifactAssignmentCharacter::name),
        )
        model.addAttribute(
            "artifactSetOptions",
            artifactCatalogService.allSets().entries.sortedBy(Map.Entry<String, String>::value),
        )
        val mainStatsBySlot = listOf("flower", "plume", "sands", "goblet", "circlet")
            .associateWith(artifactOptimizationService::mainStatOptions)
        model.addAttribute(
            "artifactMainStatOptions",
            mainStatsBySlot.values.flatten().distinctBy { it.key },
        )
        model.addAttribute(
            "artifactMainStatSlots",
            mainStatsBySlot.entries
                .flatMap { (slotKey, options) -> options.map { it.key to slotKey } }
                .groupBy(Pair<String, String>::first, Pair<String, String>::second),
        )
        model.addAttribute("artifactSubstatOptions", artifactOptimizationService.substatOptions())
        return "inventory-artifacts"
    }

    @PostMapping("/artifacts/assign")
    fun assignArtifact(
        @RequestParam artifactIndex: Int,
        @RequestParam(required = false) location: String?,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String = try {
        val result = artifactManagementService.assign(principal.id, artifactIndex, location)
        redirectAttributes.addFlashAttribute(
            "successMessage",
            messages.get(
                if (result.swapped) "artifacts.assignment.swapped" else "artifacts.assignment.saved",
            ),
        )
        result.characterKey?.let { redirectAttributes.addAttribute("character", it) }
        "redirect:/inventory/artifacts"
    } catch (_: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute("errorMessage", messages.get("artifacts.change.invalid"))
        "redirect:/inventory/artifacts"
    } catch (_: IllegalStateException) {
        redirectAttributes.addFlashAttribute("errorMessage", messages.get("inventory.import.first"))
        "redirect:/inventory/artifacts"
    }

    @PostMapping("/artifacts/update")
    fun updateArtifact(
        @RequestParam artifactIndex: Int,
        @RequestParam setKey: String,
        @RequestParam slotKey: String,
        @RequestParam level: Int,
        @RequestParam rarity: Int,
        @RequestParam mainStatKey: String,
        @RequestParam(defaultValue = "false") locked: Boolean,
        @RequestParam(defaultValue = "false") astralMark: Boolean,
        @RequestParam(defaultValue = "false") elixirCrafted: Boolean,
        @RequestParam(name = "substatKeys", required = false) substatKeys: List<String>?,
        @RequestParam(name = "substatValues", required = false) substatValues: List<String>?,
        @RequestParam(required = false) totalRolls: Int?,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String = mutateArtifact(redirectAttributes) {
        artifactManagementService.update(
            principal.id,
            artifactIndex,
            artifactMutationRequest(
                setKey,
                slotKey,
                level,
                rarity,
                mainStatKey,
                locked,
                astralMark,
                elixirCrafted,
                substatKeys,
                substatValues,
                totalRolls,
            ),
        )
        redirectAttributes.addFlashAttribute(
            "successMessage",
            messages.get("artifacts.change.saved"),
        )
    }

    @PostMapping("/artifacts/create")
    fun createArtifact(
        @RequestParam setKey: String,
        @RequestParam slotKey: String,
        @RequestParam level: Int,
        @RequestParam rarity: Int,
        @RequestParam mainStatKey: String,
        @RequestParam(defaultValue = "false") locked: Boolean,
        @RequestParam(defaultValue = "false") astralMark: Boolean,
        @RequestParam(defaultValue = "false") elixirCrafted: Boolean,
        @RequestParam(name = "substatKeys", required = false) substatKeys: List<String>?,
        @RequestParam(name = "substatValues", required = false) substatValues: List<String>?,
        @RequestParam(required = false) totalRolls: Int?,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String = mutateArtifact(redirectAttributes) {
        artifactManagementService.create(
            principal.id,
            artifactMutationRequest(
                setKey,
                slotKey,
                level,
                rarity,
                mainStatKey,
                locked,
                astralMark,
                elixirCrafted,
                substatKeys,
                substatValues,
                totalRolls,
            ),
        )
        redirectAttributes.addFlashAttribute(
            "successMessage",
            messages.get("artifacts.create.saved"),
        )
    }

    @GetMapping("/artifact-optimizer")
    fun artifactOptimizer(
        @RequestParam(required = false) character: String?,
        @RequestParam(required = false) profile: String?,
        @RequestParam(required = false) customTargets: Boolean?,
        @RequestParam(required = false) sandsMain: String?,
        @RequestParam(required = false) gobletMain: String?,
        @RequestParam(required = false) circletMain: String?,
        @RequestParam(name = "substats", required = false) substats: List<String>?,
        @RequestParam(name = "statKeys", required = false) statKeys: List<String>?,
        @RequestParam(name = "statPriorities", required = false) statPriorities: List<Int>?,
        @RequestParam(name = "statMinimums", required = false) statMinimums: List<String>?,
        @RequestParam(name = "priorityStats", required = false) priorityStats: List<String>?,
        @RequestParam(name = "priorityMinimums", required = false)
        priorityMinimums: List<String>?,
        @RequestParam(name = "priorityMaximums", required = false)
        priorityMaximums: List<String>?,
        @RequestParam(name = "statBonusKeys", required = false) statBonusKeys: List<String>?,
        @RequestParam(name = "statBonusValues", required = false) statBonusValues: List<String>?,
        @RequestParam(required = false) additionalCritRate: Double?,
        @RequestParam(required = false) setMode: String?,
        @RequestParam(required = false) firstSet: String?,
        @RequestParam(required = false) firstSetCount: Int?,
        @RequestParam(required = false) secondSet: String?,
        @RequestParam(required = false) secondSetCount: Int?,
        @RequestParam(required = false) shared: String?,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val snapshot = snapshotStore.current(principal.id)
        val artifactCounts = snapshot?.artifacts.orEmpty()
            .mapNotNull { it.location }
            .groupingBy(GoodKeyNormalizer::normalize)
            .eachCount()
        val characters = snapshot?.characters.orEmpty()
            .map { state ->
                val definition = catalogService.findCharacter(state.key.lowercase())
                OptimizerCharacterOption(
                    key = state.key,
                    name = definition?.name ?: GoodKeyNormalizer.humanize(state.key),
                    iconUrl = definition?.iconImageUrl,
                    artifactCount = artifactCounts[GoodKeyNormalizer.normalize(state.key)] ?: 0,
                )
            }
            .distinctBy { GoodKeyNormalizer.normalize(it.key) }
            .sortedWith(
                compareByDescending<OptimizerCharacterOption> { it.artifactCount }
                    .thenBy { it.name },
            )
        val selectedCharacter = characters.find {
            GoodKeyNormalizer.normalize(it.key) == GoodKeyNormalizer.normalize(character.orEmpty())
        } ?: characters.firstOrNull()
        val selectedState = selectedCharacter?.let { selected ->
            snapshot?.characters?.find {
                GoodKeyNormalizer.normalize(it.key) == GoodKeyNormalizer.normalize(selected.key)
            }
        }
        val selectedArtifacts = selectedCharacter?.let { selected ->
            snapshot?.artifacts?.filter {
                GoodKeyNormalizer.normalize(it.location.orEmpty()) ==
                    GoodKeyNormalizer.normalize(selected.key)
            }
        }.orEmpty()
        val availableSets = availableArtifactSets(snapshot?.artifacts.orEmpty())
        val savedProfile = selectedCharacter?.let {
            artifactOptimizerProfileService.find(principal.id, it.key)
        }
        val customProfiles = selectedCharacter?.let {
            artifactOptimizerCustomProfileService.findAll(principal.id, it.key)
        }.orEmpty()
        val requestedCustomProfileId = profile
            ?.removePrefix(CUSTOM_PROFILE_PREFIX)
            ?.takeIf { profile.startsWith(CUSTOM_PROFILE_PREFIX) }
            ?.toLongOrNull()
        val requestedCustomProfile = selectedCharacter?.let {
            artifactOptimizerCustomProfileService.find(
                principal.id,
                it.key,
                requestedCustomProfileId,
            )
        }
        val savedCustomProfile = selectedCharacter?.let {
            artifactOptimizerCustomProfileService.find(
                principal.id,
                it.key,
                savedProfile?.customProfileId,
            )
        }
        val characterAdditionalStats = selectedCharacter?.let {
            characterTargetService.find(principal.id, it.key)?.additionalStats
        }.orEmpty()
        val sharedConfiguration = artifactOptimizerSharingService.find(shared)
        val hasConfigurationQuery = listOf(
            profile,
            sandsMain,
            gobletMain,
            circletMain,
            setMode,
            firstSet,
            secondSet,
        ).any { it != null } || customTargets != null || substats != null ||
            statKeys != null || statPriorities != null || statMinimums != null ||
            priorityStats != null || priorityMinimums != null || priorityMaximums != null ||
            statBonusKeys != null || statBonusValues != null ||
            additionalCritRate != null || firstSetCount != null || secondSetCount != null
        val selectedProfile = when {
            requestedCustomProfile != null -> requestedCustomProfile.profile
            !profile.isNullOrBlank() -> ArtifactOptimizationProfile.fromKey(profile)
            !hasConfigurationQuery && sharedConfiguration != null ->
                sharedConfiguration.profile
            !hasConfigurationQuery && savedCustomProfile != null -> savedCustomProfile.profile
            !hasConfigurationQuery && savedProfile != null -> savedProfile.profile
            else -> artifactOptimizationService.inferProfile(selectedArtifacts)
        }
        val baseTargets = when {
            !hasConfigurationQuery && sharedConfiguration != null -> sharedConfiguration.targets
            requestedCustomProfile != null -> requestedCustomProfile.targets
            !hasConfigurationQuery && savedProfile != null -> savedProfile.targets
            else -> null
        }
        val targets = if (baseTargets != null) {
            artifactOptimizationService.createTargets(
                profile = selectedProfile,
                custom = baseTargets.custom,
                requestedMainStats = baseTargets.mainStats,
                requestedPriorityStats = baseTargets.substatPriorities,
                requestedMinimumTargets = baseTargets.minimumTargets,
                requestedMaximumTargets = baseTargets.maximumTargets,
                requestedAdditionalStats = if (
                    !hasConfigurationQuery && sharedConfiguration != null
                ) {
                    baseTargets.additionalStats
                } else {
                    characterAdditionalStats
                },
            )
        } else {
            artifactOptimizationService.createTargets(
                profile = selectedProfile,
                custom = customTargets == true,
                requestedMainStats = mapOf(
                    "sands" to sandsMain?.takeUnless { it == "auto" },
                    "goblet" to gobletMain?.takeUnless { it == "auto" },
                    "circlet" to circletMain?.takeUnless { it == "auto" },
                ),
                requestedSubstats = substats.orEmpty(),
                requestedPriorityStats = orderedPriorities(
                    selectedStats = substats.orEmpty(),
                    statKeys = statKeys.orEmpty(),
                    priorities = statPriorities.orEmpty(),
                    orderedStats = priorityStats.orEmpty(),
                ),
                requestedMinimumTargets = minimumTargets(
                    if (priorityStats != null) priorityStats else statKeys.orEmpty(),
                    if (priorityMinimums != null) priorityMinimums else statMinimums.orEmpty(),
                ),
                requestedMaximumTargets = maximumTargets(
                    priorityStats.orEmpty(),
                    priorityMaximums.orEmpty(),
                ),
                requestedAdditionalStats = if (statBonusKeys != null || statBonusValues != null) {
                    additionalStats(
                        statBonusKeys.orEmpty(),
                        statBonusValues.orEmpty(),
                    )
                } else {
                    characterAdditionalStats
                },
                additionalCritRate = additionalCritRate ?: 0.0,
            )
        }
        val requestedSetSelection = when {
            !hasConfigurationQuery && sharedConfiguration != null ->
                sharedConfiguration.setSelection
            requestedCustomProfile != null -> requestedCustomProfile.setSelection
            !hasConfigurationQuery && savedProfile != null -> savedProfile.setSelection
            else ->
            ArtifactSetSelection(
                mode = ArtifactSetSelectionMode.fromKey(setMode),
                requirements = listOfNotNull(
                    firstSet?.let { ArtifactSetTarget(it, firstSetCount ?: 2) },
                    secondSet?.let { ArtifactSetTarget(it, secondSetCount ?: 2) },
                ),
            )
        }
        val setSelection = artifactOptimizationService.createSetSelection(
            modeKey = requestedSetSelection.mode.key,
            requestedTargets = requestedSetSelection.requirements,
            availableSetKeys = availableSets.map(OptimizerArtifactSetOption::key),
        )
        val selectedWeapon = selectedCharacter?.let { selected ->
            snapshot?.weapons?.find {
                GoodKeyNormalizer.normalize(it.location.orEmpty()) ==
                    GoodKeyNormalizer.normalize(selected.key)
            }
        }
        val result = if (snapshot != null && selectedState != null) {
            val baseStats = optimizerCombatStatService.resolve(
                selectedState,
                selectedWeapon,
                targets.additionalStats,
            )
            artifactOptimizationService.optimize(
                snapshot,
                selectedState,
                selectedProfile,
                targets,
                setSelection,
                baseStats,
            )
        } else {
            null
        }

        model.addAttribute("snapshot", snapshot)
        model.addAttribute("optimizerCharacters", characters)
        model.addAttribute("selectedCharacter", selectedCharacter)
        model.addAttribute("profiles", ArtifactOptimizationProfile.entries)
        model.addAttribute("customProfiles", customProfiles)
        model.addAttribute(
            "selectedProfileSelection",
            when {
                requestedCustomProfile != null -> requestedCustomProfile.selectionKey
                !hasConfigurationQuery && savedCustomProfile != null ->
                    savedCustomProfile.selectionKey
                else -> selectedProfile.key
            },
        )
        model.addAttribute(
            "selectedCustomProfile",
            requestedCustomProfile ?: if (!hasConfigurationQuery) savedCustomProfile else null,
        )
        model.addAttribute("selectedProfile", selectedProfile)
        model.addAttribute("optimizerTargets", targets)
        model.addAttribute(
            "mainStatOptions",
            listOf("sands", "goblet", "circlet")
                .associateWith(artifactOptimizationService::mainStatOptions),
        )
        model.addAttribute("substatOptions", artifactOptimizationService.substatOptions())
        model.addAttribute(
            "additionalStatOptions",
            artifactOptimizationService.optimizerAdditionalStatOptions(),
        )
        val selectedStatOptions = targets.substatPriorities.mapNotNull { selectedKey ->
            artifactOptimizationService.substatOptions().find { it.key == selectedKey }
        }
        model.addAttribute("selectedSubstatOptions", selectedStatOptions)
        model.addAttribute(
            "unselectedSubstatOptions",
            artifactOptimizationService.substatOptions().filter {
                it.key !in targets.substatPriorities
            },
        )
        model.addAttribute("artifactSetOptions", availableSets)
        model.addAttribute("optimizerSetSelection", setSelection)
        model.addAttribute(
            "optimizerProfileSaved",
            savedProfile != null && !hasConfigurationQuery && sharedConfiguration == null,
        )
        model.addAttribute("sharedOptimizerConfiguration", sharedConfiguration)
        model.addAttribute("optimization", result)
        model.addAttribute(
            "artifactIcons",
            artifactCatalogService.imageUrls(result?.displayedArtifacts.orEmpty()),
        )
        return "artifact-optimizer"
    }

    @PostMapping("/artifact-optimizer/profile")
    fun saveArtifactOptimizerProfile(
        @RequestParam character: String,
        @RequestParam profile: String,
        @RequestParam(required = false) baseProfile: String?,
        @RequestParam(defaultValue = "false") customTargets: Boolean,
        @RequestParam(defaultValue = "auto") sandsMain: String,
        @RequestParam(defaultValue = "auto") gobletMain: String,
        @RequestParam(defaultValue = "auto") circletMain: String,
        @RequestParam(name = "substats", required = false) substats: List<String>?,
        @RequestParam(name = "statKeys", required = false) statKeys: List<String>?,
        @RequestParam(name = "statPriorities", required = false) statPriorities: List<Int>?,
        @RequestParam(name = "statMinimums", required = false) statMinimums: List<String>?,
        @RequestParam(name = "priorityStats", required = false) priorityStats: List<String>?,
        @RequestParam(name = "priorityMinimums", required = false)
        priorityMinimums: List<String>?,
        @RequestParam(name = "priorityMaximums", required = false)
        priorityMaximums: List<String>?,
        @RequestParam(name = "statBonusKeys", required = false) statBonusKeys: List<String>?,
        @RequestParam(name = "statBonusValues", required = false) statBonusValues: List<String>?,
        @RequestParam(defaultValue = "0") additionalCritRate: Double,
        @RequestParam(defaultValue = "current") setMode: String,
        @RequestParam(required = false) firstSet: String?,
        @RequestParam(defaultValue = "2") firstSetCount: Int,
        @RequestParam(required = false) secondSet: String?,
        @RequestParam(defaultValue = "2") secondSetCount: Int,
        @RequestParam(required = false) customProfileName: String?,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String {
        val snapshot = snapshotStore.current(principal.id)
        val selectedCharacter = snapshot?.characters?.find {
            GoodKeyNormalizer.normalize(it.key) == GoodKeyNormalizer.normalize(character)
        }
        if (snapshot == null || selectedCharacter == null) {
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                messages.get("optimizer.profile.saveError"),
            )
            return "redirect:/inventory/artifact-optimizer"
        }

        val selectedCustomProfileId = profile
            .removePrefix(CUSTOM_PROFILE_PREFIX)
            .takeIf { profile.startsWith(CUSTOM_PROFILE_PREFIX) }
            ?.toLongOrNull()
        val selectedCustomProfile = artifactOptimizerCustomProfileService.find(
            principal.id,
            selectedCharacter.key,
            selectedCustomProfileId,
        )
        val requestedCustomProfileName = customProfileName
            ?.trim()
            ?.takeIf {
                profile.startsWith(CUSTOM_PROFILE_PREFIX) && it.isNotBlank()
            }
        if (profile == "custom-new" && requestedCustomProfileName == null) {
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                messages.get("optimizer.profile.nameRequired"),
            )
            redirectAttributes.addAttribute("character", selectedCharacter.key)
            return "redirect:/inventory/artifact-optimizer"
        }
        val selectedProfile = selectedCustomProfile?.profile
            ?: ArtifactOptimizationProfile.fromKey(
                if (profile == "custom-new") baseProfile else profile,
            )
        val targets = artifactOptimizationService.createTargets(
            profile = selectedProfile,
            custom = customTargets || selectedCustomProfile != null ||
                requestedCustomProfileName != null,
            requestedMainStats = mapOf(
                "sands" to sandsMain.takeUnless { it == "auto" },
                "goblet" to gobletMain.takeUnless { it == "auto" },
                "circlet" to circletMain.takeUnless { it == "auto" },
            ),
            requestedSubstats = substats.orEmpty(),
            requestedPriorityStats = orderedPriorities(
                selectedStats = substats.orEmpty(),
                statKeys = statKeys.orEmpty(),
                priorities = statPriorities.orEmpty(),
                orderedStats = priorityStats.orEmpty(),
            ),
            requestedMinimumTargets = minimumTargets(
                if (priorityStats != null) priorityStats else statKeys.orEmpty(),
                if (priorityMinimums != null) priorityMinimums else statMinimums.orEmpty(),
            ),
            requestedMaximumTargets = maximumTargets(
                priorityStats.orEmpty(),
                priorityMaximums.orEmpty(),
            ),
            requestedAdditionalStats = additionalStats(
                statBonusKeys.orEmpty(),
                statBonusValues.orEmpty(),
            ),
            additionalCritRate = additionalCritRate,
        )
        val availableSets = availableArtifactSets(snapshot.artifacts)
        val setSelection = artifactOptimizationService.createSetSelection(
            modeKey = setMode,
            requestedTargets = listOfNotNull(
                firstSet?.let { ArtifactSetTarget(it, firstSetCount) },
                secondSet?.let { ArtifactSetTarget(it, secondSetCount) },
            ),
            availableSetKeys = availableSets.map(OptimizerArtifactSetOption::key),
        )
        synchronizeOptimizerAdditionalStats(
            userId = principal.id,
            characterKey = selectedCharacter.key,
            optimizerStats = targets.additionalStats,
        )
        val savedCustomProfile = (
            requestedCustomProfileName
                ?: selectedCustomProfile?.name
            )?.let { profileName ->
            artifactOptimizerCustomProfileService.save(
                userId = principal.id,
                characterKey = selectedCharacter.key,
                id = selectedCustomProfile?.id,
                name = profileName,
                profile = selectedProfile,
                targets = targets,
                setSelection = setSelection,
            )
        }
        artifactOptimizerProfileService.save(
            userId = principal.id,
            characterKey = selectedCharacter.key,
            profile = selectedProfile,
            targets = targets,
            setSelection = setSelection,
            customProfileId = savedCustomProfile?.id ?: selectedCustomProfile?.id,
        )
        redirectAttributes.addFlashAttribute(
            "successMessage",
            messages.get("optimizer.profile.saved", selectedCharacter.key),
        )
        redirectAttributes.addAttribute("character", selectedCharacter.key)
        return "redirect:/inventory/artifact-optimizer"
    }

    @PostMapping("/artifact-optimizer/profile/reset")
    fun resetArtifactOptimizerProfile(
        @RequestParam character: String,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String {
        artifactOptimizerProfileService.delete(principal.id, character)
        redirectAttributes.addFlashAttribute(
            "successMessage",
            messages.get("optimizer.profile.reset"),
        )
        redirectAttributes.addAttribute("character", character)
        return "redirect:/inventory/artifact-optimizer"
    }

    @PostMapping("/artifact-optimizer/profile/custom/delete")
    fun deleteCustomArtifactOptimizerProfile(
        @RequestParam character: String,
        @RequestParam customProfileId: Long,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String {
        val activeProfile = artifactOptimizerProfileService.find(principal.id, character)
        artifactOptimizerCustomProfileService.delete(
            principal.id,
            character,
            customProfileId,
        )
        if (activeProfile?.customProfileId == customProfileId) {
            artifactOptimizerProfileService.save(
                userId = principal.id,
                characterKey = character,
                profile = activeProfile.profile,
                targets = activeProfile.targets,
                setSelection = activeProfile.setSelection,
                customProfileId = null,
            )
        }
        redirectAttributes.addFlashAttribute(
            "successMessage",
            messages.get("optimizer.profile.customDeleted"),
        )
        redirectAttributes.addAttribute("character", character)
        return "redirect:/inventory/artifact-optimizer"
    }

    @PostMapping("/artifact-optimizer/profile/share")
    fun shareArtifactOptimizerProfile(
        @RequestParam character: String,
        @RequestParam profile: String,
        @RequestParam(defaultValue = "false") customTargets: Boolean,
        @RequestParam(defaultValue = "auto") sandsMain: String,
        @RequestParam(defaultValue = "auto") gobletMain: String,
        @RequestParam(defaultValue = "auto") circletMain: String,
        @RequestParam(name = "substats", required = false) substats: List<String>?,
        @RequestParam(name = "statKeys", required = false) statKeys: List<String>?,
        @RequestParam(name = "statPriorities", required = false) statPriorities: List<Int>?,
        @RequestParam(name = "statMinimums", required = false) statMinimums: List<String>?,
        @RequestParam(name = "priorityStats", required = false) priorityStats: List<String>?,
        @RequestParam(name = "priorityMinimums", required = false)
        priorityMinimums: List<String>?,
        @RequestParam(name = "priorityMaximums", required = false)
        priorityMaximums: List<String>?,
        @RequestParam(name = "statBonusKeys", required = false) statBonusKeys: List<String>?,
        @RequestParam(name = "statBonusValues", required = false) statBonusValues: List<String>?,
        @RequestParam(defaultValue = "0") additionalCritRate: Double,
        @RequestParam(defaultValue = "current") setMode: String,
        @RequestParam(required = false) firstSet: String?,
        @RequestParam(defaultValue = "2") firstSetCount: Int,
        @RequestParam(required = false) secondSet: String?,
        @RequestParam(defaultValue = "2") secondSetCount: Int,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String {
        val snapshot = snapshotStore.current(principal.id)
        if (snapshot == null) return "redirect:/inventory/artifact-optimizer"
        val selectedCustomProfileId = profile
            .removePrefix(CUSTOM_PROFILE_PREFIX)
            .takeIf { profile.startsWith(CUSTOM_PROFILE_PREFIX) }
            ?.toLongOrNull()
        val selectedProfile = artifactOptimizerCustomProfileService.find(
            principal.id,
            character,
            selectedCustomProfileId,
        )?.profile ?: ArtifactOptimizationProfile.fromKey(profile)
        val targets = artifactOptimizationService.createTargets(
            profile = selectedProfile,
            custom = customTargets,
            requestedMainStats = mapOf(
                "sands" to sandsMain.takeUnless { it == "auto" },
                "goblet" to gobletMain.takeUnless { it == "auto" },
                "circlet" to circletMain.takeUnless { it == "auto" },
            ),
            requestedSubstats = substats.orEmpty(),
            requestedPriorityStats = orderedPriorities(
                selectedStats = substats.orEmpty(),
                statKeys = statKeys.orEmpty(),
                priorities = statPriorities.orEmpty(),
                orderedStats = priorityStats.orEmpty(),
            ),
            requestedMinimumTargets = minimumTargets(
                if (priorityStats != null) priorityStats else statKeys.orEmpty(),
                if (priorityMinimums != null) priorityMinimums else statMinimums.orEmpty(),
            ),
            requestedMaximumTargets = maximumTargets(
                priorityStats.orEmpty(),
                priorityMaximums.orEmpty(),
            ),
            requestedAdditionalStats = additionalStats(
                statBonusKeys.orEmpty(),
                statBonusValues.orEmpty(),
            ),
            additionalCritRate = additionalCritRate,
        )
        val availableSets = availableArtifactSets(snapshot.artifacts)
        val setSelection = artifactOptimizationService.createSetSelection(
            modeKey = setMode,
            requestedTargets = listOfNotNull(
                firstSet?.let { ArtifactSetTarget(it, firstSetCount) },
                secondSet?.let { ArtifactSetTarget(it, secondSetCount) },
            ),
            availableSetKeys = availableSets.map(OptimizerArtifactSetOption::key),
        )
        val sharedConfiguration = artifactOptimizerSharingService.create(
            principal.id,
            selectedProfile,
            targets,
            setSelection,
        )
        redirectAttributes.addFlashAttribute(
            "successMessage",
            messages.get("optimizer.share.created"),
        )
        redirectAttributes.addAttribute("character", character)
        redirectAttributes.addAttribute("shared", sharedConfiguration.token)
        return "redirect:/inventory/artifact-optimizer"
    }

    @GetMapping("/weapons")
    fun weapons(
        @RequestParam(defaultValue = "") query: String,
        @RequestParam(defaultValue = "0") page: Int,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val snapshot = snapshotStore.current(principal.id)
        val normalizedQuery = query.trim()
        val filtered = snapshot?.weapons.orEmpty()
            .filter {
                normalizedQuery.isBlank() ||
                    it.name.contains(normalizedQuery, ignoreCase = true) ||
                    it.location?.contains(normalizedQuery, ignoreCase = true) == true
            }
            .sortedWith(compareByDescending<PlayerWeapon> { it.level }.thenBy { it.name })
        val weaponPage = paginate(filtered, page)

        model.addAttribute("snapshot", snapshot)
        model.addAttribute("weapons", weaponPage.items)
        model.addAttribute("weaponIcons", weaponCatalogService.imageUrls(weaponPage.items))
        model.addAttribute("page", weaponPage.page)
        model.addAttribute("totalPages", weaponPage.totalPages)
        model.addAttribute("totalItems", weaponPage.totalItems)
        model.addAttribute("query", normalizedQuery)
        return "inventory-weapons"
    }

    @PostMapping("/upload")
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String {
        val error = when {
            file.isEmpty -> messages.get("inventory.import.selectFile")
            file.size > GoodImportService.MAX_FILE_SIZE ->
                messages.get("good.error.fileSize")
            else -> null
        }
        if (error != null) {
            redirectAttributes.addFlashAttribute("errorMessage", error)
            return "redirect:/inventory/items"
        }

        return try {
            val snapshot = snapshotStore.save(principal.id, file.bytes)
            redirectAttributes.addFlashAttribute(
                "successMessage",
                messages.get(
                    "inventory.import.success",
                    snapshot.formatVersion,
                    snapshot.characters.size,
                ),
            )
            "redirect:/inventory/items"
        } catch (exception: GoodImportException) {
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                messages.get(exception.messageKey, *exception.messageArguments),
            )
            "redirect:/inventory/items"
        } catch (_: Exception) {
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                messages.get("inventory.import.persistenceError"),
            )
            "redirect:/inventory/items"
        }
    }

    private fun <T> paginate(items: List<T>, requestedPage: Int): InventoryPage<T> {
        val totalPages = maxOf(1, (items.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = requestedPage.coerceIn(0, totalPages - 1)
        val fromIndex = page * PAGE_SIZE
        val toIndex = minOf(items.size, fromIndex + PAGE_SIZE)
        return InventoryPage(
            items = if (fromIndex < items.size) items.subList(fromIndex, toIndex) else emptyList(),
            page = page,
            totalPages = totalPages,
            totalItems = items.size,
        )
    }

    private fun artifactMutationRequest(
        setKey: String,
        slotKey: String,
        level: Int,
        rarity: Int,
        mainStatKey: String,
        locked: Boolean,
        astralMark: Boolean,
        elixirCrafted: Boolean,
        substatKeys: List<String>?,
        substatValues: List<String>?,
        totalRolls: Int?,
    ): ArtifactMutationRequest {
        val substats = substatKeys.orEmpty().zip(substatValues.orEmpty())
            .mapNotNull { (key, rawValue) ->
                if (key.isBlank()) {
                    null
                } else {
                    ArtifactStatInput(
                        key = key,
                        value = rawValue.replace(',', '.').toDoubleOrNull()
                            ?: throw IllegalArgumentException("Invalid substat value"),
                    )
                }
            }
        return ArtifactMutationRequest(
            setKey = setKey,
            slotKey = slotKey,
            level = level,
            rarity = rarity,
            mainStatKey = mainStatKey,
            locked = locked,
            astralMark = astralMark,
            elixirCrafted = elixirCrafted,
            substats = substats,
            totalRolls = totalRolls,
        )
    }

    private fun mutateArtifact(
        redirectAttributes: RedirectAttributes,
        mutation: () -> Unit,
    ): String = try {
        mutation()
        "redirect:/inventory/artifacts"
    } catch (_: IllegalArgumentException) {
        redirectAttributes.addFlashAttribute(
            "errorMessage",
            messages.get("artifacts.change.invalid"),
        )
        "redirect:/inventory/artifacts"
    } catch (_: IllegalStateException) {
        redirectAttributes.addFlashAttribute(
            "errorMessage",
            messages.get("inventory.import.first"),
        )
        "redirect:/inventory/artifacts"
    }

    private fun availableArtifactSets(
        artifacts: List<PlayerArtifact>,
    ): List<OptimizerArtifactSetOption> {
        val inventoryCounts = artifacts.asSequence()
            .filter { it.rarity == 5 }
            .groupingBy { GoodKeyNormalizer.normalize(it.setKey) }
            .eachCount()
        return artifactCatalogService.allSets()
            .map { (key, name) ->
                OptimizerArtifactSetOption(
                    key = key,
                    name = name,
                    pieces = inventoryCounts[key] ?: 0,
                )
            }
            .sortedBy(OptimizerArtifactSetOption::name)
    }

    private fun orderedPriorities(
        selectedStats: Collection<String>,
        statKeys: List<String>,
        priorities: List<Int>,
        orderedStats: List<String> = emptyList(),
    ): List<String> {
        if (orderedStats.isNotEmpty()) return orderedStats.distinct()
        if (statKeys.isEmpty() || priorities.isEmpty()) return selectedStats.toList()
        val selected = selectedStats.toSet()
        return statKeys.zip(priorities)
            .filter { (key) -> key in selected }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map(Pair<String, Int>::first)
    }

    private fun minimumTargets(
        statKeys: List<String>,
        minimums: List<String>,
    ): Map<String, Double> = statKeys.zip(minimums)
        .mapNotNull { (key, value) ->
            value.replace(',', '.').toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { key to it }
        }
        .toMap()

    private fun maximumTargets(
        statKeys: List<String>,
        maximums: List<String>,
    ): Map<String, Double> = (
        statKeys.zip(maximums)
            .mapNotNull { (key, value) ->
                value.replace(',', '.').toDoubleOrNull()
                    ?.takeIf { it > 0.0 }
                    ?.let { key to it }
            }
            .toMap() + if ("critRate_" in statKeys) {
            emptyMap()
        } else {
            mapOf("critRate_" to 100.0)
        }
        )

    private fun additionalStats(
        statKeys: List<String>,
        values: List<String>,
    ): Map<String, Double> {
        val allowedKeys = artifactOptimizationService.optimizerAdditionalStatOptions()
            .mapTo(mutableSetOf()) { it.key }
        return statKeys.zip(values)
            .mapNotNull { (key, value) ->
                value.replace(',', '.').toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it != 0.0 && key in allowedKeys }
                    ?.coerceIn(-MAX_ADDITIONAL_STAT, MAX_ADDITIONAL_STAT)
                    ?.let { key to it }
            }
            .toMap()
    }

    private fun synchronizeOptimizerAdditionalStats(
        userId: Long,
        characterKey: String,
        optimizerStats: Map<String, Double>,
    ) {
        val optimizerKeys = artifactOptimizationService.optimizerAdditionalStatOptions()
            .mapTo(mutableSetOf()) { it.key }
        val existingStats = characterTargetService.find(userId, characterKey)
            ?.additionalStats
            .orEmpty()
        characterTargetService.saveAdditionalStats(
            userId = userId,
            characterKey = characterKey,
            additionalStats = existingStats.filterKeys { it !in optimizerKeys } + optimizerStats,
        )
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val MAX_ADDITIONAL_STAT = 100_000.0
        private const val CUSTOM_PROFILE_PREFIX = "custom-"
        private val EXPERIENCE_ITEM_KEYS = setOf(
            "heroswit",
            "adventurersexperience",
            "wanderersadvice",
        )
    }
}

data class InventoryItemRow(
    val key: String,
    val name: String,
    val amount: Long,
    val imageUrl: String?,
    val buildMaterial: Boolean,
    val categoryMessageKey: String?,
    val craftableAmount: Long,
    val availableAmount: Long,
)

data class InventoryPage<T>(
    val items: List<T>,
    val page: Int,
    val totalPages: Int,
    val totalItems: Int,
)

data class OptimizerCharacterOption(
    val key: String,
    val name: String,
    val iconUrl: String?,
    val artifactCount: Int,
)

data class OptimizerArtifactSetOption(
    val key: String,
    val name: String,
    val pieces: Int,
)

data class InventoryArtifactRow(
    val inventoryIndex: Int,
    val artifact: PlayerArtifact,
) {
    val editorSubstats: List<ArtifactEditorStat>
        get() = List(4) { index ->
            artifact.substats.getOrNull(index)?.let {
                ArtifactEditorStat(it.key, it.value)
            } ?: ArtifactEditorStat("", null)
        }
}

data class ArtifactEditorStat(
    val key: String,
    val value: Double?,
)

data class ArtifactAssignmentCharacter(
    val key: String,
    val name: String,
)
