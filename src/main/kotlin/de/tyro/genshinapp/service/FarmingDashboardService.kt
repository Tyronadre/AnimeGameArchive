package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterProgress
import de.tyro.genshinapp.model.CharacterProgressForm
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.InventoryMaterialBalance
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialRequirement
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerSnapshot
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

@Service
class FarmingDashboardService(
    private val catalogService: CharacterCatalogService,
    private val targetService: CharacterTargetService,
    private val materialCalculator: MaterialCalculator,
    private val planningService: PlayerPlanningService,
    private val materialCraftingService: MaterialCraftingService,
    private val domainScheduleService: DomainScheduleService,
    private val artifactOptimizerProfileService: ArtifactOptimizerProfileService,
    private val artifactOptimizationService: ArtifactOptimizationService,
    private val artifactCatalogService: ArtifactCatalogService,
    private val characterWeaponTargetService: CharacterWeaponTargetService,
    private val weaponDataService: WeaponDataService,
    private val weaponPlanningService: WeaponPlanningService,
) {
    fun create(
        userId: Long,
        snapshot: PlayerSnapshot,
        selections: Set<DashboardGoalSelection>,
        automaticPlan: Boolean = false,
    ): FarmingDashboard {
        val charactersByKey = catalogService.getCharacters().associateBy {
            GoodKeyNormalizer.normalize(it.key)
        }
        val savedTargets = targetService.findAll(userId)
        val summaries = mutableListOf<DashboardGoalProgress>()
        val characterRequirements = mutableListOf<CharacterGoalRequirements>()

        selections.sortedWith(
            compareBy<DashboardGoalSelection> { it.type.ordinal }
                .thenBy { charactersByKey[it.characterKey]?.name ?: it.characterKey },
        ).forEach { selection ->
            val character = charactersByKey[selection.characterKey] ?: return@forEach
            val state = planningService.findCharacterState(snapshot, character.key)
            when (selection.type) {
                DashboardGoalType.CHARACTER -> {
                    val progress = progressFor(state, savedTargets[selection.characterKey])
                    if (!progress.owned) return@forEach
                    val requirements = materialCalculator.calculate(character, progress)
                    characterRequirements += CharacterGoalRequirements(character, requirements)
                    summaries += DashboardGoalProgress(
                        characterKey = character.key,
                        characterName = character.name,
                        iconUrl = character.iconImageUrl,
                        type = DashboardGoalType.CHARACTER,
                        currentLevel = progress.level,
                        targetLevel = progress.targetLevel,
                        currentTalentTotal = progress.normalTalent +
                            progress.skillTalent + progress.burstTalent,
                        targetTalentTotal = progress.targetNormalTalent +
                            progress.targetSkillTalent + progress.targetBurstTalent,
                        artifactScore = null,
                        weaponName = null,
                        weaponCurrentLevel = null,
                        weaponTargetLevel = null,
                        complete = requirements.isEmpty(),
                    )
                }

                DashboardGoalType.ARTIFACTS -> {
                    if (state == null) return@forEach
                    val artifacts = equippedArtifacts(snapshot, character.key)
                    val savedProfile = artifactOptimizerProfileService.find(userId, character.key)
                    val profile = savedProfile?.profile
                        ?: artifactOptimizationService.inferProfile(artifacts)
                    val targets = savedProfile?.targets
                        ?: ArtifactOptimizationTargets.defaults(profile)
                    val evaluations = artifacts.map {
                        artifactOptimizationService.evaluate(it, profile, targets)
                    }
                    val averageScore = evaluations.map(ArtifactEvaluation::score)
                        .average()
                        .takeUnless(Double::isNaN)
                        ?: 0.0
                    summaries += DashboardGoalProgress(
                        characterKey = character.key,
                        characterName = character.name,
                        iconUrl = character.iconImageUrl,
                        type = DashboardGoalType.ARTIFACTS,
                        currentLevel = null,
                        targetLevel = null,
                        currentTalentTotal = null,
                        targetTalentTotal = null,
                        artifactScore = averageScore,
                        weaponName = null,
                        weaponCurrentLevel = null,
                        weaponTargetLevel = null,
                        complete = false,
                    )
                    val equippedWeapon = snapshot.weapons.find {
                        GoodKeyNormalizer.normalize(it.location.orEmpty()) ==
                            GoodKeyNormalizer.normalize(character.key)
                    }
                    val savedWeaponTarget = characterWeaponTargetService.find(
                        userId,
                        character.key,
                    )?.takeIf {
                        it.weaponKey == GoodKeyNormalizer.normalize(
                            equippedWeapon?.key.orEmpty(),
                        )
                    }
                    if (equippedWeapon != null && savedWeaponTarget != null) {
                        val definition = weaponDataService.find(equippedWeapon.key)
                        if (definition != null) {
                            val weaponRequirements = weaponPlanningService.calculateRequirements(
                                equippedWeapon,
                                definition,
                                savedWeaponTarget.targetLevel,
                            )
                            characterRequirements += CharacterGoalRequirements(
                                character,
                                weaponRequirements,
                            )
                            val summaryIndex = summaries.lastIndex
                            summaries[summaryIndex] = summaries[summaryIndex].copy(
                                weaponName = definition.name,
                                weaponCurrentLevel = equippedWeapon.level,
                                weaponTargetLevel = savedWeaponTarget.targetLevel,
                            )
                        }
                    }
                }
            }
        }

        val recommendations = linkedMapOf<String, MutableFarmRecommendation>()
        val rosterRequirements = rosterCharacterRequirements(
            snapshot,
            charactersByKey,
            savedTargets,
            AUTOMATIC_ROSTER_CHARACTER_COUNT,
        )
        val selectedMaterialRecommendationKeys: Set<String>?
        val expandedMaterialRequirements: List<CharacterGoalRequirements>
        if (automaticPlan) {
            selectedMaterialRecommendationKeys = null
            expandedMaterialRequirements = mergeCharacterRequirements(
                characterRequirements + rosterRequirements,
            )
        } else {
            val selectedMaterialRecommendations = linkedMapOf<String, MutableFarmRecommendation>()
            addMaterialRecommendations(
                userId,
                snapshot,
                characterRequirements,
                selectedMaterialRecommendations,
            )
            selectedMaterialRecommendationKeys = selectedMaterialRecommendations.keys.toSet()
            expandedMaterialRequirements = if (selectedMaterialRecommendationKeys.isEmpty()) {
                emptyList()
            } else {
                mergeCharacterRequirements(characterRequirements + rosterRequirements)
            }
        }
        addMaterialRecommendations(
            userId,
            snapshot,
            expandedMaterialRequirements,
            recommendations,
            allowedRecommendationKeys = selectedMaterialRecommendationKeys,
        )
        addArtifactRecommendations(
            userId,
            snapshot,
            summaries.filter { it.type == DashboardGoalType.ARTIFACTS },
            recommendations,
        )
        if (automaticPlan && recommendations.values.none { !it.activity.resin }) {
            addAutomaticFreeMaterialFallback(
                userId,
                snapshot,
                rosterRequirements,
                recommendations,
            )
        }

        return FarmingDashboard(
            goals = summaries,
            recommendations = recommendations.values
                .map(MutableFarmRecommendation::toRecommendation)
                .sortedWith(
                    compareByDescending<FarmingRecommendation> { it.priority }
                        .thenBy { it.title },
                ),
        )
    }

    private fun progressFor(
        state: de.tyro.genshinapp.model.PlayerCharacterState?,
        target: CharacterTargetValues?,
    ): CharacterProgress {
        val form = CharacterProgressForm()
        state?.let(form::apply)
        target?.applyTo(form)
        return form.normalized()
    }

    private fun addMaterialRecommendations(
        userId: Long,
        snapshot: PlayerSnapshot,
        characterRequirements: List<CharacterGoalRequirements>,
        recommendations: MutableMap<String, MutableFarmRecommendation>,
        freeOnly: Boolean = false,
        allowedRecommendationKeys: Set<String>? = null,
    ) {
        val aggregate = linkedMapOf<MaterialIdentity, MaterialRequirement>()
        characterRequirements.flatMap(CharacterGoalRequirements::requirements)
            .forEach { requirement ->
                val key = MaterialIdentity(requirement.id, requirement.name)
                val previous = aggregate[key]
                aggregate[key] = requirement.copy(
                    amount = (previous?.amount ?: 0L) + requirement.amount,
                )
            }
        val balances = cachedMaterialBalances(userId, snapshot, aggregate.values.toList())

        balances.filter { it.missing > 0 }
            .filter { balance ->
                when (balance.category) {
                    MaterialCategory.TALENT_BOOK ->
                        domainScheduleService.isTalentBookFarmable(balance.id)
                    MaterialCategory.WEAPON_ASCENSION ->
                        domainScheduleService.isWeaponMaterialFarmable(balance.id)
                    else -> true
                }
            }
            .forEach { balance ->
            val descriptor = farmDescriptor(balance)
            if (freeOnly && descriptor.activity.resin) return@forEach
            if (allowedRecommendationKeys != null &&
                descriptor.key !in allowedRecommendationKeys
            ) {
                return@forEach
            }
            val contributors = characterRequirements.filter { goal ->
                goal.requirements.any { it.id == balance.id && it.name == balance.name }
            }
            val recommendation = recommendations.getOrPut(descriptor.key) {
                MutableFarmRecommendation(
                    key = descriptor.key,
                    activity = descriptor.activity,
                    title = descriptor.title,
                    imageUrl = balance.imageUrl,
                    href = "/inventory/missing?materialId=${balance.id}",
                )
            }
            recommendation.addMaterial(balance)
            contributors.forEach { recommendation.addCharacter(it.character) }
            recommendation.addUrgency(
                balance.missing.toDouble() / balance.required.coerceAtLeast(1),
            )
        }
    }

    private fun cachedMaterialBalances(
        userId: Long,
        snapshot: PlayerSnapshot,
        requirements: List<MaterialRequirement>,
    ): List<InventoryMaterialBalance> {
        if (requirements.isEmpty()) return emptyList()
        val key = MaterialBalanceCacheKey(
            userId = userId,
            snapshotRevision = snapshot.revision,
            requirementSignature = requirementsSignature(requirements),
        )
        return materialBalanceCache.computeIfAbsent(key) {
            planningService.calculateBalances(requirements, snapshot)
        }.also {
            pruneMaterialBalanceCache(userId, snapshot.revision)
        }
    }

    private fun requirementsSignature(requirements: List<MaterialRequirement>): String =
        requirements.sortedWith(
            compareBy<MaterialRequirement> { it.id }
                .thenBy { it.name }
                .thenBy { it.amount },
        ).joinToString("|") { requirement ->
            "${requirement.id}:${requirement.name}:${requirement.amount}"
        }

    private fun pruneMaterialBalanceCache(userId: Long, currentRevision: Long) {
        if (materialBalanceCache.size <= MAX_MATERIAL_BALANCE_CACHE_ENTRIES) return
        materialBalanceCache.keys
            .filter { it.userId == userId && it.snapshotRevision != currentRevision }
            .forEach(materialBalanceCache::remove)
        if (materialBalanceCache.size > MAX_MATERIAL_BALANCE_CACHE_ENTRIES) {
            materialBalanceCache.clear()
        }
    }

    private fun addAutomaticFreeMaterialFallback(
        userId: Long,
        snapshot: PlayerSnapshot,
        rosterRequirements: List<CharacterGoalRequirements>,
        recommendations: MutableMap<String, MutableFarmRecommendation>,
    ) {
        addMaterialRecommendations(
            userId,
            snapshot,
            rosterRequirements,
            recommendations,
            freeOnly = true,
        )
    }

    private fun rosterCharacterRequirements(
        snapshot: PlayerSnapshot,
        charactersByKey: Map<String, CharacterDefinition>,
        savedTargets: Map<String, CharacterTargetValues>,
        limit: Int,
    ): List<CharacterGoalRequirements> =
        snapshot.characters.mapNotNull { state ->
            val normalizedKey = GoodKeyNormalizer.normalize(state.key)
            val character = charactersByKey[normalizedKey]
                ?: if (normalizedKey == TRAVELER_KEY) {
                    charactersByKey[AETHER_KEY] ?: charactersByKey[LUMINE_KEY]
                } else {
                    catalogService.findCharacter(normalizedKey)
                }
                ?: return@mapNotNull null
            val targetKey = GoodKeyNormalizer.normalize(character.key)
            val progress = progressFor(state, savedTargets[targetKey])
            if (!progress.owned) return@mapNotNull null
            if (
                progress.level >= progress.targetLevel &&
                progress.normalTalent >= progress.targetNormalTalent &&
                progress.skillTalent >= progress.targetSkillTalent &&
                progress.burstTalent >= progress.targetBurstTalent
            ) {
                return@mapNotNull null
            }
            CharacterFreeFallbackCandidate(
                requirements = CharacterGoalRequirements(
                    character,
                    materialCalculator.calculate(character, progress),
                ),
                level = progress.level,
                talentTotal = progress.normalTalent +
                    progress.skillTalent + progress.burstTalent,
            )
        }.sortedWith(
            compareByDescending<CharacterFreeFallbackCandidate> { it.level }
                .thenByDescending { it.talentTotal },
        ).take(limit)
            .map(CharacterFreeFallbackCandidate::requirements)

    private fun mergeCharacterRequirements(
        requirements: List<CharacterGoalRequirements>,
    ): List<CharacterGoalRequirements> {
        val grouped = linkedMapOf<String, MutableCharacterGoalRequirements>()
        requirements.forEach { goal ->
            val key = GoodKeyNormalizer.normalize(goal.character.key)
            val aggregate = grouped.getOrPut(key) {
                MutableCharacterGoalRequirements(goal.character)
            }
            goal.requirements.forEach(aggregate::add)
        }
        return grouped.values.map(MutableCharacterGoalRequirements::toRequirements)
    }

    private fun farmDescriptor(balance: InventoryMaterialBalance): FarmDescriptor {
        if (balance.id == CHARACTER_EXPERIENCE_ID) {
            return FarmDescriptor(
                "ley-line:experience",
                FarmingActivity.CHARACTER_EXPERIENCE,
                balance.name,
            )
        }
        if (balance.id == MORA_ID || balance.name.equals("Mora", ignoreCase = true)) {
            return FarmDescriptor("ley-line:mora", FarmingActivity.MORA, balance.name)
        }
        if (balance.id == MYSTIC_ENHANCEMENT_ORE_ID) {
            return FarmDescriptor(
                "weapon-ore",
                FarmingActivity.WEAPON_ORE,
                balance.name,
            )
        }

        val info = materialCraftingService.infoFor(balance.id)
        val category = info?.category ?: balance.category
        val activity = when (category) {
            MaterialCategory.TALENT_BOOK -> FarmingActivity.TALENT_DOMAIN
            MaterialCategory.WEAPON_ASCENSION -> FarmingActivity.WEAPON_DOMAIN
            MaterialCategory.WEEKLY_BOSS -> FarmingActivity.WEEKLY_BOSS
            MaterialCategory.WORLD_BOSS,
            MaterialCategory.GEM,
            -> FarmingActivity.WORLD_BOSS
            MaterialCategory.COLLECTABLE -> FarmingActivity.REGIONAL_SPECIALTY
            MaterialCategory.ENEMY_DROP -> FarmingActivity.ENEMY_DROPS
            MaterialCategory.OTHER -> FarmingActivity.OTHER
        }
        val familyKey = info?.familyKey ?: balance.id.toString()
        return FarmDescriptor(
            key = "${activity.key}:$familyKey",
            activity = activity,
            title = balance.name,
        )
    }

    private fun addArtifactRecommendations(
        userId: Long,
        snapshot: PlayerSnapshot,
        artifactGoals: List<DashboardGoalProgress>,
        recommendations: MutableMap<String, MutableFarmRecommendation>,
    ) {
        artifactGoals.forEach { goal ->
            val artifacts = equippedArtifacts(snapshot, goal.characterKey)
            if (artifacts.isEmpty()) return@forEach
            val savedProfile = artifactOptimizerProfileService.find(userId, goal.characterKey)
            val profile = savedProfile?.profile ?: artifactOptimizationService.inferProfile(artifacts)
            val targets = savedProfile?.targets ?: ArtifactOptimizationTargets.defaults(profile)
            val scoredArtifacts = artifacts.map { artifact ->
                artifact to artifactOptimizationService.evaluate(artifact, profile, targets).score
            }
            val setKeys = targetArtifactSets(
                artifacts,
                scoredArtifacts,
                savedProfile?.setSelection,
            )
            val urgency = 1.0 - ((goal.artifactScore ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
            setKeys.forEach { setKey ->
                val setName = artifactCatalogService.setName(setKey)
                    ?: artifacts.firstOrNull {
                        GoodKeyNormalizer.normalize(it.setKey) == setKey
                    }?.setName
                    ?: GoodKeyNormalizer.humanize(setKey)
                val recommendation = recommendations.getOrPut("artifacts:$setKey") {
                    MutableFarmRecommendation(
                        key = "artifacts:$setKey",
                        activity = FarmingActivity.ARTIFACTS,
                        title = setName,
                        imageUrl = artifactCatalogService.imageUrl(setKey, "flower"),
                        href = "/inventory/artifact-optimizer?character=${goal.characterKey}",
                    )
                }
                recommendation.addCharacter(
                    CharacterReference(goal.characterKey, goal.characterName, goal.iconUrl),
                )
                recommendation.addArtifactScore(goal.artifactScore ?: 0.0)
                recommendation.addUrgency(urgency)
            }
        }
    }

    private fun targetArtifactSets(
        artifacts: List<PlayerArtifact>,
        scoredArtifacts: List<Pair<PlayerArtifact, Double>>,
        setSelection: ArtifactSetSelection?,
    ): List<String> {
        if (setSelection?.mode == ArtifactSetSelectionMode.CUSTOM &&
            setSelection.requirements.isNotEmpty()
        ) {
            return setSelection.requirements.map(ArtifactSetTarget::setKey)
        }

        val counts = artifacts.groupingBy {
            GoodKeyNormalizer.normalize(it.setKey)
        }.eachCount()
        val activeSets = when {
            counts.any { it.value >= 4 } ->
                listOf(requireNotNull(counts.maxByOrNull(Map.Entry<String, Int>::value)).key)
            counts.count { it.value >= 2 } >= 2 ->
                counts.filterValues { it >= 2 }.keys.take(2)
            counts.any { it.value >= 2 } ->
                listOf(requireNotNull(counts.maxByOrNull(Map.Entry<String, Int>::value)).key)
            else -> emptyList()
        }
        if (activeSets.isNotEmpty()) return activeSets
        return scoredArtifacts.minByOrNull { it.second }
            ?.first
            ?.setKey
            ?.let(GoodKeyNormalizer::normalize)
            ?.let(::listOf)
            .orEmpty()
    }

    private fun equippedArtifacts(snapshot: PlayerSnapshot, characterKey: String): List<PlayerArtifact> {
        val normalizedKey = GoodKeyNormalizer.normalize(characterKey)
        return snapshot.artifacts.filter {
            GoodKeyNormalizer.normalize(it.location.orEmpty()) == normalizedKey
        }
    }

    private data class CharacterGoalRequirements(
        val character: CharacterDefinition,
        val requirements: List<MaterialRequirement>,
    )

    private class MutableCharacterGoalRequirements(
        val character: CharacterDefinition,
    ) {
        private val requirements = linkedMapOf<MaterialIdentity, MaterialRequirement>()

        fun add(requirement: MaterialRequirement) {
            val key = MaterialIdentity(requirement.id, requirement.name)
            val previous = requirements[key]
            requirements[key] = requirement.copy(
                amount = (previous?.amount ?: 0L) + requirement.amount,
            )
        }

        fun toRequirements(): CharacterGoalRequirements =
            CharacterGoalRequirements(character, requirements.values.toList())
    }

    private data class CharacterFreeFallbackCandidate(
        val requirements: CharacterGoalRequirements,
        val level: Int,
        val talentTotal: Int,
    )

    private data class MaterialIdentity(
        val id: Int,
        val name: String,
    )

    private data class MaterialBalanceCacheKey(
        val userId: Long,
        val snapshotRevision: Long,
        val requirementSignature: String,
    )

    private data class FarmDescriptor(
        val key: String,
        val activity: FarmingActivity,
        val title: String,
    )

    private class MutableFarmRecommendation(
        val key: String,
        val activity: FarmingActivity,
        var title: String,
        var imageUrl: String?,
        var href: String,
    ) {
        private val characters = linkedMapOf<String, CharacterReference>()
        private val materials = linkedMapOf<Int, FarmingMaterialNeed>()
        private val artifactScores = mutableListOf<Double>()
        private var urgencyTotal = 0.0
        private var urgencySamples = 0

        fun addCharacter(character: CharacterDefinition) {
            addCharacter(
                CharacterReference(character.key, character.name, character.iconImageUrl),
            )
        }

        fun addCharacter(character: CharacterReference) {
            characters[GoodKeyNormalizer.normalize(character.key)] = character
        }

        fun addMaterial(balance: InventoryMaterialBalance) {
            val current = materials[balance.id]
            materials[balance.id] = FarmingMaterialNeed(
                id = balance.id,
                name = balance.name,
                required = (current?.required ?: 0L) + balance.required,
                covered = (current?.covered ?: 0L) +
                    (balance.required - balance.missing).coerceAtLeast(0L),
                missing = (current?.missing ?: 0L) + balance.missing,
                imageUrl = balance.imageUrl,
            )
        }

        fun addArtifactScore(score: Double) {
            artifactScores += score
        }

        fun addUrgency(urgency: Double) {
            urgencyTotal += urgency.coerceIn(0.0, 1.0)
            urgencySamples++
        }

        fun toRecommendation(): FarmingRecommendation {
            val urgency = if (urgencySamples == 0) 0.0 else urgencyTotal / urgencySamples
            val recommendationHref = if (
                activity != FarmingActivity.ARTIFACTS && characters.size == 1
            ) {
                "/characters/${characters.values.first().key}"
            } else {
                href
            }
            return FarmingRecommendation(
                key = key,
                activity = activity,
                title = title,
                imageUrl = imageUrl,
                characters = characters.values.toList(),
                materials = materials.values.sortedByDescending(FarmingMaterialNeed::missing),
                artifactScore = artifactScores.takeIf(List<Double>::isNotEmpty)?.average(),
                impactPercent = (urgency * 100).roundToInt(),
                priority = characters.size * 1_000 + (urgency * 100).roundToInt(),
                href = recommendationHref,
            )
        }
    }

    companion object {
        private const val CHARACTER_EXPERIENCE_ID = 0
        private const val MORA_ID = 202
        private const val MYSTIC_ENHANCEMENT_ORE_ID = 104013
        private const val AUTOMATIC_ROSTER_CHARACTER_COUNT = 24
        private const val MAX_MATERIAL_BALANCE_CACHE_ENTRIES = 128
        private const val TRAVELER_KEY = "traveler"
        private const val AETHER_KEY = "aether"
        private const val LUMINE_KEY = "lumine"
    }

    private val materialBalanceCache =
        ConcurrentHashMap<MaterialBalanceCacheKey, List<InventoryMaterialBalance>>()
}

data class FarmingDashboard(
    val goals: List<DashboardGoalProgress>,
    val recommendations: List<FarmingRecommendation>,
) {
    val weeklyRecommendations: List<FarmingRecommendation>
        get() = recommendations.filter { it.activity == FarmingActivity.WEEKLY_BOSS }

    val resinRecommendations: List<FarmingRecommendation>
        get() = recommendations.filter {
            it.activity.resin && it.activity != FarmingActivity.WEEKLY_BOSS
        }

    val freeRecommendations: List<FarmingRecommendation>
        get() = recommendations.filterNot { it.activity.resin }

    val primaryRecommendation: FarmingRecommendation?
        get() = recommendations.firstOrNull()

    val additionalRecommendations: List<FarmingRecommendation>
        get() = recommendations.drop(1)

    val allGoalsComplete: Boolean
        get() = goals.isNotEmpty() && goals.all(DashboardGoalProgress::complete)
}

data class DashboardGoalProgress(
    val characterKey: String,
    val characterName: String,
    val iconUrl: String?,
    val type: DashboardGoalType,
    val currentLevel: Int?,
    val targetLevel: Int?,
    val currentTalentTotal: Int?,
    val targetTalentTotal: Int?,
    val artifactScore: Double?,
    val weaponName: String?,
    val weaponCurrentLevel: Int?,
    val weaponTargetLevel: Int?,
    val complete: Boolean,
)

enum class FarmingActivity(
    val key: String,
    val messageKey: String,
    val actionMessageKey: String,
    val icon: String,
    val resin: Boolean,
    val resinCost: Int,
) {
    CHARACTER_EXPERIENCE(
        "experience",
        "dashboard.activity.experience",
        "dashboard.action.experience",
        "✦",
        true,
        20,
    ),
    MORA("mora", "dashboard.activity.mora", "dashboard.action.mora", "₥", true, 20),
    TALENT_DOMAIN(
        "talent",
        "dashboard.activity.talent",
        "dashboard.action.talent",
        "◆",
        true,
        20,
    ),
    WEAPON_DOMAIN(
        "weapon",
        "dashboard.activity.weapon",
        "dashboard.action.weapon",
        "◇",
        true,
        20,
    ),
    WEAPON_ORE(
        "weapon-ore",
        "dashboard.activity.weaponOre",
        "dashboard.action.weaponOre",
        "⬡",
        false,
        0,
    ),
    WORLD_BOSS(
        "world-boss",
        "dashboard.activity.worldBoss",
        "dashboard.action.worldBoss",
        "♜",
        true,
        40,
    ),
    WEEKLY_BOSS(
        "weekly-boss",
        "dashboard.activity.weeklyBoss",
        "dashboard.action.weeklyBoss",
        "✹",
        true,
        30,
    ),
    REGIONAL_SPECIALTY(
        "collectable",
        "dashboard.activity.collectable",
        "dashboard.action.collectable",
        "❧",
        false,
        0,
    ),
    ENEMY_DROPS(
        "enemy",
        "dashboard.activity.enemy",
        "dashboard.action.enemy",
        "⚔",
        false,
        0,
    ),
    ARTIFACTS(
        "artifacts",
        "dashboard.activity.artifacts",
        "dashboard.action.artifacts",
        "✧",
        true,
        20,
    ),
    OTHER("other", "dashboard.activity.other", "dashboard.action.other", "◇", false, 0),
}

data class FarmingRecommendation(
    val key: String,
    val activity: FarmingActivity,
    val title: String,
    val imageUrl: String?,
    val characters: List<CharacterReference>,
    val materials: List<FarmingMaterialNeed>,
    val artifactScore: Double?,
    val impactPercent: Int,
    val priority: Int,
    val href: String,
) {
    val requiredTotal: Long
        get() = materials.sumOf(FarmingMaterialNeed::required)

    val coveredTotal: Long
        get() = materials.sumOf(FarmingMaterialNeed::covered)

    val missingTotal: Long
        get() = materials.sumOf(FarmingMaterialNeed::missing)
}

data class FarmingMaterialNeed(
    val id: Int,
    val name: String,
    val required: Long,
    val covered: Long,
    val missing: Long,
    val imageUrl: String?,
)

data class CharacterReference(
    val key: String,
    val name: String,
    val iconUrl: String?,
)
