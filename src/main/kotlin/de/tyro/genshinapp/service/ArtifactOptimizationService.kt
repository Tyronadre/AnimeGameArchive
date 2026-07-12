package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerArtifactStat
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerSnapshot
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

@Service
class ArtifactOptimizationService(
    private val artifactCatalogService: ArtifactCatalogService,
    private val artifactEvaluationCacheService: ArtifactEvaluationCacheService? = null,
) {
    fun inferProfile(artifacts: Collection<PlayerArtifact>): ArtifactOptimizationProfile {
        val variableMainStats = artifacts
            .filter { it.slotKey.lowercase() in setOf("sands", "goblet", "circlet") }
            .map(PlayerArtifact::mainStatKey)
        return when {
            variableMainStats.count { it == "eleMas" } >= 2 ->
                ArtifactOptimizationProfile.REACTION
            "enerRech_" in variableMainStats &&
                variableMainStats.any { it == "heal_" } ->
                ArtifactOptimizationProfile.ENERGY_SUPPORT
            "def_" in variableMainStats ->
                ArtifactOptimizationProfile.DEFENSE
            "hp_" in variableMainStats ->
                ArtifactOptimizationProfile.HP
            else -> ArtifactOptimizationProfile.ATTACK
        }
    }

    fun createTargets(
        profile: ArtifactOptimizationProfile,
        custom: Boolean,
        requestedMainStats: Map<String, String?> = emptyMap(),
        requestedSubstats: Collection<String> = emptyList(),
        requestedPriorityStats: Collection<String> = emptyList(),
        requestedMinimumTargets: Map<String, Double> = emptyMap(),
        requestedMaximumTargets: Map<String, Double> = emptyMap(),
        requestedAdditionalStats: Map<String, Double> = emptyMap(),
        additionalCritRate: Double = 0.0,
    ): ArtifactOptimizationTargets {
        val additionalStats = (
            requestedAdditionalStats +
                if (additionalCritRate != 0.0) {
                    mapOf("critRate_" to additionalCritRate)
                } else {
                    emptyMap()
                }
            )
            .filterKeys { it in OPTIMIZER_BONUS_STAT_KEYS }
            .mapValues { (key, value) ->
                value.coerceIn(
                    if (key == "critRate_") -CRIT_RATE_CAP else -MAX_TARGET_VALUE,
                    if (key == "critRate_") CRIT_RATE_CAP else MAX_TARGET_VALUE,
                )
            }
            .filterValues { it != 0.0 }
        if (!custom) {
            return ArtifactOptimizationTargets.defaults(profile).copy(
                additionalStats = additionalStats,
            )
        }
        val mainStats = VARIABLE_MAIN_STAT_SLOTS.associateWith { slot ->
            requestedMainStats[slot]?.takeIf { requested ->
                MAIN_STATS_BY_SLOT.getValue(slot).any { (key) -> key == requested }
            }
        }
        val priorities = (requestedPriorityStats.ifEmpty { requestedSubstats })
            .filter { it in SUBSTAT_WEIGHTS }
            .distinct()
            .ifEmpty { ArtifactOptimizationTargets.defaultPriorities(profile) }
        val maximumTargets = (mapOf("critRate_" to CRIT_RATE_CAP) + requestedMaximumTargets)
            .filterKeys { it in SUBSTAT_WEIGHTS }
            .mapValues { (key, value) ->
                value.coerceIn(0.0, if (key == "critRate_") CRIT_RATE_CAP else MAX_TARGET_VALUE)
            }
            .filterValues { it > 0.0 }
        val minimumTargets = requestedMinimumTargets
            .filterKeys { it in SUBSTAT_WEIGHTS }
            .mapValues { (key, value) ->
                value.coerceIn(
                    0.0,
                    maximumTargets[key]
                        ?: if (key == "critRate_") CRIT_RATE_CAP else MAX_TARGET_VALUE,
                )
            }
            .filterValues { it > 0.0 }
        return ArtifactOptimizationTargets(
            custom = true,
            mainStats = mainStats,
            substatPriorities = priorities,
            minimumTargets = minimumTargets,
            maximumTargets = maximumTargets,
            additionalStats = additionalStats,
        )
    }

    fun createSetSelection(
        modeKey: String?,
        requestedTargets: Collection<ArtifactSetTarget> = emptyList(),
        availableSetKeys: Collection<String> = emptyList(),
    ): ArtifactSetSelection {
        val mode = ArtifactSetSelectionMode.fromKey(modeKey)
        if (mode != ArtifactSetSelectionMode.CUSTOM) {
            return ArtifactSetSelection(mode)
        }
        val available = availableSetKeys
            .mapTo(mutableSetOf(), GoodKeyNormalizer::normalize)
        var remainingPieces = 4
        val selectedKeys = mutableSetOf<String>()
        val requirements = requestedTargets.mapNotNull { target ->
            val setKey = GoodKeyNormalizer.normalize(target.setKey)
            val count = if (target.count == 4) 4 else 2
            if (setKey.isBlank() || setKey !in available ||
                setKey in selectedKeys || count > remainingPieces
            ) {
                null
            } else {
                remainingPieces -= count
                selectedKeys += setKey
                ArtifactSetTarget(setKey, count)
            }
        }.take(2)
        return ArtifactSetSelection(mode, requirements)
    }

    fun mainStatOptions(slotKey: String): List<ArtifactStatOption> =
        MAIN_STATS_BY_SLOT[slotKey].orEmpty().map { (key) ->
            ArtifactStatOption(key, statMessageKey(key))
        }

    fun substatOptions(): List<ArtifactStatOption> =
        SUBSTAT_WEIGHTS.keys.map { key -> ArtifactStatOption(key, statMessageKey(key)) }

    fun additionalStatOptions(): List<ArtifactStatOption> =
        MANUAL_BONUS_STAT_KEYS.map { key -> ArtifactStatOption(key, statMessageKey(key)) }

    fun optimizerAdditionalStatOptions(): List<ArtifactStatOption> =
        OPTIMIZER_BONUS_STAT_KEYS.map { key -> ArtifactStatOption(key, statMessageKey(key)) }

    fun optimize(
        snapshot: PlayerSnapshot,
        character: PlayerCharacterState,
        profile: ArtifactOptimizationProfile,
        targets: ArtifactOptimizationTargets = ArtifactOptimizationTargets.defaults(profile),
        setSelection: ArtifactSetSelection = ArtifactSetSelection.current(),
        baseStats: OptimizerBaseStats = OptimizerBaseStats.defaults(),
    ): ArtifactOptimizationResult {
        val strategy = ArtifactScoringStrategy(profile, targets)
        val indexedArtifacts = snapshot.artifacts.mapIndexed(::IndexedArtifact)
        val characterKey = GoodKeyNormalizer.normalize(character.key)
        val currentArtifacts = indexedArtifacts
            .filter {
                GoodKeyNormalizer.normalize(it.artifact.location.orEmpty()) == characterKey
            }
            .sortedBy { SLOT_ORDER[it.artifact.slotKey.lowercase()] ?: Int.MAX_VALUE }
        val currentContexts = currentArtifacts.associate { target ->
            target.artifact.slotKey.lowercase() to buildStatsForArtifacts(
                artifacts = currentArtifacts
                    .filter { it.index != target.index }
                    .map(IndexedArtifact::artifact),
                baseStats = baseStats,
                targets = targets,
            ).totalValues
        }
        val currentPieces = currentArtifacts.map {
            score(
                it,
                strategy,
                currentArtifact = true,
                contextTotals = currentContexts[it.artifact.slotKey.lowercase()].orEmpty(),
            )
        }
        val currentBySlot = currentPieces.associateBy { it.artifact.slotKey.lowercase() }
        val currentSetPlan = createSetPlan(
            currentArtifacts.map(IndexedArtifact::artifact),
            snapshot.artifacts,
            ArtifactSetSelection.current(),
        )
        val loadoutSetPlan = createSetPlan(
            currentArtifacts.map(IndexedArtifact::artifact),
            snapshot.artifacts,
            setSelection,
        )

        val farmingOutlooks = farmingOutlooks(
            currentPieces = currentPieces,
            setPlan = currentSetPlan,
            strategy = strategy,
            contextBySlot = currentContexts,
        )
        val artifactOutlooks = artifactUpgradeOutlooks(
            currentPieces = currentPieces,
            strategy = strategy,
            contextBySlot = currentContexts,
        )
        val levelingCandidates = levelingCandidates(
            allArtifacts = indexedArtifacts,
            currentBySlot = currentBySlot,
            setPlan = currentSetPlan,
            strategy = strategy,
            contextBySlot = currentContexts,
        )
        val loadout = optimizeLoadout(
            allArtifacts = indexedArtifacts,
            currentPieces = currentPieces,
            setPlan = loadoutSetPlan,
            strategy = strategy,
            selectedCharacterKey = characterKey,
            baseStats = baseStats,
        )
        val currentBuildStats = buildStatsForArtifacts(
            currentArtifacts.map(IndexedArtifact::artifact),
            baseStats,
            targets,
        )

        return ArtifactOptimizationResult(
            profile = profile,
            targets = targets,
            currentPieces = currentPieces,
            currentAverageScore = averageLoadoutScore(currentPieces),
            currentGrade = grade(averageLoadoutScore(currentPieces)),
            currentBuildStats = currentBuildStats,
            setPlan = loadoutSetPlan,
            farmingOutlooks = farmingOutlooks,
            artifactOutlooks = artifactOutlooks,
            levelingCandidates = levelingCandidates,
            levelingRecommendations = currentPieces.map { currentPiece ->
                ArtifactLevelingRecommendation(
                    currentPiece = currentPiece,
                    candidates = levelingCandidates.filter {
                        it.piece.artifact.slotKey.equals(
                            currentPiece.artifact.slotKey,
                            ignoreCase = true,
                        )
                    },
                )
            },
            loadout = loadout,
        )
    }

    fun evaluate(
        artifact: PlayerArtifact,
        profile: ArtifactOptimizationProfile,
        targets: ArtifactOptimizationTargets = ArtifactOptimizationTargets.defaults(profile),
        contextTotals: Map<String, Double> = emptyMap(),
    ): ArtifactEvaluation = cachedEvaluateArtifact(
        artifact,
        ArtifactScoringStrategy(profile, targets),
        contextTotals,
    )

    fun summarizeCurrentBuild(
        artifacts: List<PlayerArtifact>,
        baseStats: OptimizerBaseStats,
    ): ArtifactBuildStats = buildStatsForArtifacts(
        artifacts = artifacts,
        baseStats = baseStats,
        targets = ArtifactOptimizationTargets(
            custom = true,
            mainStats = emptyMap(),
            substatPriorities = emptyList(),
        ),
        includeAllStats = true,
    )

    private fun farmingOutlooks(
        currentPieces: List<ScoredArtifact>,
        setPlan: ArtifactSetPlan,
        strategy: ArtifactScoringStrategy,
        contextBySlot: Map<String, Map<String, Double>>,
    ): List<ArtifactFarmingOutlook> {
        val setCounts = currentPieces.groupingBy {
            GoodKeyNormalizer.normalize(it.artifact.setKey)
        }.eachCount()
        val targetSetKeys = setPlan.requirements.map(ArtifactSetRequirement::setKey)
            .ifEmpty {
                setCounts.entries.sortedByDescending(Map.Entry<String, Int>::value)
                    .take(1)
                    .map(Map.Entry<String, Int>::key)
            }

        return targetSetKeys.mapNotNull { setKey ->
            val setCount = setCounts[setKey]
            val targets = if (setCount == null || setCount >= 4) {
                currentPieces.associateBy { it.artifact.slotKey.lowercase() }
            } else {
                currentPieces
                    .filter { GoodKeyNormalizer.normalize(it.artifact.setKey) == setKey }
                    .associateBy { it.artifact.slotKey.lowercase() }
            }
            if (targets.isEmpty()) return@mapNotNull null

            val random = Random(
                stableSeed(setKey, strategy.seedKey, targets.values.sumOf { it.score }),
            )
            var improvements = 0
            repeat(FARMING_SIMULATIONS) {
                val generated = generateDomainArtifact(setKey, random)
                val target = targets[generated.slotKey.lowercase()]
                if (target != null &&
                    evaluateArtifact(
                        generated,
                        strategy,
                        contextBySlot[generated.slotKey.lowercase()].orEmpty(),
                    ).score > target.score + SCORE_EPSILON
                ) {
                    improvements++
                }
            }

            val chancePerOnSetDrop = improvements.toDouble() / FARMING_SIMULATIONS
            val chancePerDomainFiveStar = chancePerOnSetDrop * DOMAIN_SET_CHANCE
            ArtifactFarmingOutlook(
                setKey = setKey,
                setName = artifactCatalogService.setName(setKey)
                    ?: targets.values.first().artifact.setName,
                comparedSlots = targets.size,
                chancePerOnSetDrop = chancePerOnSetDrop,
                chancePerDomainFiveStar = chancePerDomainFiveStar,
                chanceAfterTwentyDomainDrops = probabilityAtLeastOne(
                    chancePerDomainFiveStar,
                    20,
                ),
                expectedDomainDrops = if (chancePerDomainFiveStar > 0.0) {
                    1.0 / chancePerDomainFiveStar
                } else {
                    null
                },
            )
        }
    }

    private fun levelingCandidates(
        allArtifacts: List<IndexedArtifact>,
        currentBySlot: Map<String, ScoredArtifact>,
        setPlan: ArtifactSetPlan,
        strategy: ArtifactScoringStrategy,
        contextBySlot: Map<String, Map<String, Double>>,
    ): List<ArtifactLevelingCandidate> {
        val currentIndices = currentBySlot.values.mapTo(mutableSetOf()) { it.inventoryIndex }
        return allArtifacts.asSequence()
            .filter { it.index !in currentIndices }
            .filter { it.artifact.rarity == 5 && it.artifact.level < 20 }
            .filter { candidateCanPreserveSetPlan(it.artifact, currentBySlot, setPlan) }
            .mapNotNull { indexed ->
                val target = currentBySlot[indexed.artifact.slotKey.lowercase()]
                val context = contextBySlot[indexed.artifact.slotKey.lowercase()].orEmpty()
                val optimisticScore = optimisticScore(indexed.artifact, strategy, context)
                if (target != null && optimisticScore <= target.score + SCORE_EPSILON) {
                    null
                } else {
                    PreLevelingCandidate(indexed, target, optimisticScore)
                }
            }
            .sortedByDescending(PreLevelingCandidate::optimisticScore)
            .take(LEVELING_PREFILTER_LIMIT)
            .map { candidate ->
                simulateLevelingCandidate(
                    candidate,
                    strategy,
                    contextBySlot[candidate.indexedArtifact.artifact.slotKey.lowercase()].orEmpty(),
                )
            }
            .filter {
                it.chanceToImprove >= MINIMUM_LEVELING_CHANCE ||
                    it.averageFinalScore > it.targetScore + SCORE_EPSILON
            }
            .sortedWith(
                compareByDescending<ArtifactLevelingCandidate> { it.chanceToImprove }
                    .thenByDescending { it.averageFinalScore - it.targetScore },
            )
            .toList()
            .groupBy { it.piece.artifact.slotKey.lowercase() }
            .values
            .flatMap { candidates -> candidates.take(LEVELING_RESULTS_PER_ARTIFACT) }
            .sortedWith(
                compareBy<ArtifactLevelingCandidate> {
                    SLOT_ORDER[it.piece.artifact.slotKey.lowercase()] ?: Int.MAX_VALUE
                }.thenByDescending(ArtifactLevelingCandidate::chanceToImprove),
            )
    }

    private fun artifactUpgradeOutlooks(
        currentPieces: List<ScoredArtifact>,
        strategy: ArtifactScoringStrategy,
        contextBySlot: Map<String, Map<String, Double>>,
    ): List<ArtifactUpgradeOutlook> = currentPieces.map { piece ->
        val slotKey = piece.artifact.slotKey.lowercase()
        val random = Random(
            stableSeed(
                "piece-outlook",
                piece.artifact.setKey,
                slotKey,
                piece.score,
                strategy.seedKey,
            ),
        )
        var improvements = 0
        repeat(ARTIFACT_FARMING_SIMULATIONS) {
            val generated = generateDomainArtifact(piece.artifact.setKey, random)
            if (generated.slotKey.equals(slotKey, ignoreCase = true) &&
                evaluateArtifact(
                    generated,
                    strategy,
                    contextBySlot[slotKey].orEmpty(),
                ).score > piece.score + SCORE_EPSILON
            ) {
                improvements++
            }
        }
        val chancePerOnSetDrop = improvements.toDouble() / ARTIFACT_FARMING_SIMULATIONS
        val chancePerDomainFiveStar = chancePerOnSetDrop * DOMAIN_SET_CHANCE
        ArtifactUpgradeOutlook(
            piece = piece,
            chancePerOnSetDrop = chancePerOnSetDrop,
            chancePerDomainFiveStar = chancePerDomainFiveStar,
            expectedDomainDrops = if (chancePerDomainFiveStar > 0.0) {
                1.0 / chancePerDomainFiveStar
            } else {
                null
            },
        )
    }

    private fun simulateLevelingCandidate(
        candidate: PreLevelingCandidate,
        strategy: ArtifactScoringStrategy,
        contextTotals: Map<String, Double>,
    ): ArtifactLevelingCandidate {
        val artifact = candidate.indexedArtifact.artifact
        val targetScore = candidate.target?.score ?: 0.0
        val random = Random(
            stableSeed(
                artifact.setKey,
                artifact.slotKey,
                artifact.mainStatKey,
                candidate.indexedArtifact.index,
                strategy.seedKey,
            ),
        )
        var improvements = 0
        var totalScore = 0.0
        var totalUsefulUpgrades = 0
        var allUsefulPaths = 0
        val scores = DoubleArray(LEVELING_SIMULATIONS)
        val remainingUpgrades = remainingUpgradeMilestones(artifact)
        repeat(LEVELING_SIMULATIONS) {
            val outcome = simulateRemainingLevels(artifact, random, strategy)
            val score = evaluateArtifact(outcome.artifact, strategy, contextTotals).score
            scores[it] = score
            totalScore += score
            totalUsefulUpgrades += outcome.usefulUpgrades
            if (outcome.usefulUpgrades == remainingUpgrades) allUsefulPaths++
            if (score > targetScore + SCORE_EPSILON) improvements++
        }
        scores.sort()

        val evaluation = cachedEvaluateArtifact(artifact, strategy, contextTotals)
        return ArtifactLevelingCandidate(
            piece = scoredArtifact(candidate.indexedArtifact, evaluation, false),
            targetScore = targetScore,
            optimisticScore = candidate.optimisticScore,
            averageFinalScore = totalScore / LEVELING_SIMULATIONS,
            chanceToImprove = improvements.toDouble() / LEVELING_SIMULATIONS,
            remainingUpgrades = remainingUpgrades,
            likelyLowScore = percentile(scores, 0.1),
            likelyHighScore = percentile(scores, 0.9),
            expectedUsefulUpgrades = totalUsefulUpgrades.toDouble() / LEVELING_SIMULATIONS,
            chanceAllUpgradesUseful = allUsefulPaths.toDouble() / LEVELING_SIMULATIONS,
            chanceNextUpgradeUseful = nextUsefulUpgradeChance(artifact, strategy),
        )
    }

    private fun optimizeLoadout(
        allArtifacts: List<IndexedArtifact>,
        currentPieces: List<ScoredArtifact>,
        setPlan: ArtifactSetPlan,
        strategy: ArtifactScoringStrategy,
        selectedCharacterKey: String,
        baseStats: OptimizerBaseStats,
    ): ArtifactLoadoutSuggestion? {
        val currentIndices = currentPieces.mapTo(mutableSetOf()) { it.inventoryIndex }
        val candidates = allArtifacts
            .filter {
                it.artifact.rarity == 5 &&
                    (it.artifact.level >= MINIMUM_LOADOUT_LEVEL || it.index in currentIndices)
            }
            .groupBy { it.artifact.slotKey.lowercase() }
            .mapValues { (_, artifacts) ->
                artifacts
                    .map { score(it, strategy, it.index in currentIndices) }
                    .filter { piece ->
                        strategy.acceptsExplicitMainStat(
                            piece.artifact.slotKey.lowercase(),
                            piece.artifact.mainStatKey,
                        )
                    }
            }
        if (SLOTS.any { candidates[it].isNullOrEmpty() }) return null

        val assignments = setAssignments(setPlan)
        val comparator = Comparator<LoadoutCandidate> { first, second ->
            compareLoadoutCandidates(first, second, strategy.targets)
        }
        val bestCandidate = assignments.asSequence()
            .mapNotNull { assignment ->
                var beam = listOf(
                    loadoutCandidate(emptyList(), baseStats, strategy.targets),
                )
                SLOTS.forEachIndexed { slotIndex, slot ->
                    val requiredSet = assignment[slotIndex]
                    val slotCandidates = candidates.getValue(slot)
                        .filter {
                            requiredSet == null ||
                                GoodKeyNormalizer.normalize(it.artifact.setKey) == requiredSet
                        }
                    if (slotCandidates.isEmpty()) return@mapNotNull null
                    beam = beam.asSequence()
                        .flatMap { partial ->
                            slotCandidates.asSequence().map { piece ->
                                loadoutCandidate(
                                    partial.pieces + piece,
                                    baseStats,
                                    strategy.targets,
                                )
                            }
                        }
                        .sortedWith(comparator.reversed())
                        .take(LOADOUT_BEAM_WIDTH)
                        .toList()
                }
                beam.asSequence()
                    .filter { satisfiesSetPlan(it.pieces, setPlan) }
                    .maxWithOrNull(comparator)
            }
            .maxWithOrNull(comparator)
            ?: return null
        val bestPieces = bestCandidate.pieces
        val contextualBestPieces = contextualizePieces(bestPieces, strategy, baseStats)

        val currentAverage = averageLoadoutScore(currentPieces)
        val optimizedAverage = averageLoadoutScore(contextualBestPieces)
        val changed = bestPieces.map(ScoredArtifact::inventoryIndex).toSet() != currentIndices
        val currentMeetsSetPlan = satisfiesSetPlan(currentPieces, setPlan)
        val currentCandidate = loadoutCandidate(currentPieces, baseStats, strategy.targets)
        if (!changed || (
                currentMeetsSetPlan &&
                    comparator.compare(bestCandidate, currentCandidate) <= 0
                )
        ) {
            return null
        }

        return ArtifactLoadoutSuggestion(
            pieces = contextualBestPieces,
            currentAverageScore = currentAverage,
            optimizedAverageScore = optimizedAverage,
            scoreGain = optimizedAverage - currentAverage,
            buildStats = bestCandidate.buildStats,
            transferredPieces = contextualBestPieces.count {
                val owner = GoodKeyNormalizer.normalize(it.artifact.location.orEmpty())
                owner.isNotBlank() && owner != selectedCharacterKey
            },
        )
    }

    private fun contextualizePieces(
        pieces: List<ScoredArtifact>,
        strategy: ArtifactScoringStrategy,
        baseStats: OptimizerBaseStats,
    ): List<ScoredArtifact> = pieces.map { target ->
        val contextTotals = buildStatsForArtifacts(
            artifacts = pieces
                .filter { it.inventoryIndex != target.inventoryIndex }
                .map(ScoredArtifact::artifact),
            baseStats = baseStats,
            targets = strategy.targets,
        ).totalValues
        score(
            indexedArtifact = IndexedArtifact(target.inventoryIndex, target.artifact),
            strategy = strategy,
            currentArtifact = target.currentArtifact,
            contextTotals = contextTotals,
        )
    }

    private fun loadoutCandidate(
        pieces: List<ScoredArtifact>,
        baseStats: OptimizerBaseStats,
        targets: ArtifactOptimizationTargets,
    ): LoadoutCandidate = LoadoutCandidate(
        pieces = pieces,
        buildStats = buildStats(pieces, baseStats, targets),
        pieceScore = pieces.sumOf(ScoredArtifact::score),
    )

    private fun compareLoadoutCandidates(
        first: LoadoutCandidate,
        second: LoadoutCandidate,
        targets: ArtifactOptimizationTargets,
    ): Int {
        comparePreferLower(
            first.buildStats.critRateOverflow,
            second.buildStats.critRateOverflow,
        ).takeIf { it != 0 }?.let { return it }
        comparePreferLower(
            first.buildStats.maximumViolation,
            second.buildStats.maximumViolation,
        ).takeIf { it != 0 }?.let { return it }

        if (targets.minimumTargets.isNotEmpty()) {
            compareValues(
                first.buildStats.allMinimumTargetsMet,
                second.buildStats.allMinimumTargetsMet,
            ).takeIf { it != 0 }?.let { return it }
            compareDoubles(
                first.buildStats.minimumCompletion,
                second.buildStats.minimumCompletion,
            ).takeIf { it != 0 }?.let { return it }
            compareDoubles(
                first.buildStats.minimumCompletionSum,
                second.buildStats.minimumCompletionSum,
            ).takeIf { it != 0 }?.let { return it }
        }

        targets.substatPriorities.forEach { key ->
            compareDoubles(
                priorityValue(key, first.buildStats.totalValues, targets),
                priorityValue(key, second.buildStats.totalValues, targets),
            ).takeIf { it != 0 }?.let { return it }
        }

        compareDoubles(
            first.buildStats.critBalance,
            second.buildStats.critBalance,
        ).takeIf { it != 0 }?.let { return it }
        return compareDoubles(first.pieceScore, second.pieceScore)
    }

    private fun priorityValue(
        key: String,
        totals: Map<String, Double>,
        targets: ArtifactOptimizationTargets,
    ): Double {
        val critRate = totals.getOrDefault("critRate_", 0.0).coerceAtMost(CRIT_RATE_CAP)
        val critDamage = totals.getOrDefault("critDMG_", 0.0)
        val value = when (key) {
            "critRate_" -> minOf(critRate, critDamage / CRIT_DAMAGE_RATIO)
            "critDMG_" -> minOf(critDamage, critRate * CRIT_DAMAGE_RATIO)
            else -> totals.getOrDefault(key, 0.0)
        }
        return minOf(value, targets.maximumTargets[key] ?: Double.MAX_VALUE)
    }

    private fun comparePreferLower(first: Double, second: Double): Int =
        compareDoubles(second, first)

    private fun compareDoubles(
        first: Double,
        second: Double,
        epsilon: Double = BUILD_COMPARISON_EPSILON,
    ): Int {
        val bucketSize = epsilon.takeIf { it > 0.0 } ?: BUILD_COMPARISON_EPSILON
        val firstBucket = (first / bucketSize).roundToLong()
        val secondBucket = (second / bucketSize).roundToLong()
        return firstBucket.compareTo(secondBucket)
    }

    private fun buildStats(
        pieces: List<ScoredArtifact>,
        baseStats: OptimizerBaseStats,
        targets: ArtifactOptimizationTargets,
    ): ArtifactBuildStats = buildStatsForArtifacts(
        pieces.map(ScoredArtifact::artifact),
        baseStats,
        targets,
    )

    private fun buildStatsForArtifacts(
        artifacts: List<PlayerArtifact>,
        baseStats: OptimizerBaseStats,
        targets: ArtifactOptimizationTargets,
        includeAllStats: Boolean = false,
    ): ArtifactBuildStats {
        val artifactStats = linkedMapOf<String, Double>()
        artifacts.forEach { artifact ->
            artifact.substats.forEach { stat ->
                artifactStats[stat.key] = artifactStats.getOrDefault(stat.key, 0.0) + stat.value
            }
            val mainStatValue = artifactMainStatValue(artifact)
            if (mainStatValue > 0.0) {
                artifactStats[artifact.mainStatKey] =
                    artifactStats.getOrDefault(artifact.mainStatKey, 0.0) + mainStatValue
            }
        }
        val trackedKeys = (
            CORE_BUILD_STATS +
                targets.minimumTargets.keys +
                targets.maximumTargets.keys +
                targets.substatPriorities +
                baseStats.totals.keys +
                artifactStats.keys
            ).distinct()
        val totalValues = trackedKeys.associateWith { key ->
            baseStats.totals.getOrDefault(key, 0.0) +
                artifactStats.getOrDefault(key, 0.0)
        }
        val completions = targets.minimumTargets.map { (key, target) ->
            (totalValues.getOrDefault(key, 0.0) / target).coerceIn(0.0, 1.0)
        }
        val maximumViolations = targets.maximumTargets.map { (key, target) ->
            (totalValues.getOrDefault(key, 0.0) - target).coerceAtLeast(0.0)
        }
        val critRate = totalValues.getOrDefault("critRate_", 0.0)
        val critDamage = totalValues.getOrDefault("critDMG_", 0.0)
        val effectiveCritRate = critRate.coerceAtMost(CRIT_RATE_CAP)
        val ratioDifference = abs(critDamage - effectiveCritRate * CRIT_DAMAGE_RATIO)
        val rows = trackedKeys
            .filter { key ->
                includeAllStats ||
                    key in CORE_BUILD_STATS ||
                    key in targets.minimumTargets ||
                    key in targets.maximumTargets ||
                    key in targets.substatPriorities
            }
            .map { key ->
                val minimum = targets.minimumTargets[key]
                ArtifactBuildStat(
                    key = key,
                    messageKey = statMessageKey(key),
                    characterValue = baseStats.characterStats.getOrDefault(key, 0.0),
                    weaponValue = baseStats.weaponStats.getOrDefault(key, 0.0),
                    bonusValue = baseStats.bonusStats.getOrDefault(key, 0.0),
                    artifactValue = artifactStats.getOrDefault(key, 0.0),
                    totalValue = totalValues.getOrDefault(key, 0.0),
                    minimumTarget = minimum,
                    minimumMet = minimum == null ||
                        totalValues.getOrDefault(key, 0.0) + BUILD_COMPARISON_EPSILON >= minimum,
                    maximumTarget = targets.maximumTargets[key],
                    maximumMet = targets.maximumTargets[key]?.let {
                        totalValues.getOrDefault(key, 0.0) <= it + BUILD_COMPARISON_EPSILON
                    } ?: true,
                    priority = targets.substatPriorities.indexOf(key)
                        .takeIf { it >= 0 }
                        ?.plus(1),
                    percentage = key.endsWith("_"),
                )
            }
        return ArtifactBuildStats(
            rows = rows,
            totalValues = totalValues,
            critRateOverflow = (critRate - CRIT_RATE_CAP).coerceAtLeast(0.0),
            critBalance = 1.0 / (1.0 + ratioDifference),
            allMinimumTargetsMet = completions.all { it >= 1.0 },
            minimumCompletion = completions.minOrNull() ?: 1.0,
            minimumCompletionSum = completions.sum(),
            allMaximumTargetsMet = maximumViolations.all { it <= BUILD_COMPARISON_EPSILON },
            maximumViolation = maximumViolations.sum(),
        )
    }

    private fun artifactMainStatValue(artifact: PlayerArtifact): Double {
        val values = MAIN_STAT_MAX_VALUES[artifact.rarity] ?: return 0.0
        val maximum = values[artifact.mainStatKey] ?: return 0.0
        val maximumLevel = when (artifact.rarity) {
            5 -> 20
            4 -> 16
            3 -> 12
            else -> return 0.0
        }
        val level = artifact.level.coerceIn(0, maximumLevel)
        val factor = if (maximumLevel == 20) {
            FIVE_STAR_MAIN_STAT_FACTORS[level]
        } else {
            val scaledIndex = (level.toDouble() / maximumLevel * 20).toInt().coerceIn(0, 20)
            FIVE_STAR_MAIN_STAT_FACTORS[scaledIndex]
        }
        return maximum * factor
    }

    private fun setAssignments(setPlan: ArtifactSetPlan): List<List<String?>> {
        if (setPlan.requirements.isEmpty()) return listOf(List(SLOTS.size) { null })
        val choices = setPlan.requirements.map(ArtifactSetRequirement::setKey) + null
        val assignments = mutableListOf<List<String?>>()

        fun build(current: MutableList<String?>) {
            if (current.size == SLOTS.size) {
                val hasRequiredAssignments = setPlan.requirements.all { requirement ->
                    current.count { it == requirement.setKey } >= requirement.count
                }
                if (hasRequiredAssignments) assignments += current.toList()
                return
            }
            choices.forEach { choice ->
                current += choice
                build(current)
                current.removeLast()
            }
        }
        build(mutableListOf())
        return assignments
    }

    private fun createSetPlan(
        currentArtifacts: List<PlayerArtifact>,
        allArtifacts: List<PlayerArtifact>,
        selection: ArtifactSetSelection,
    ): ArtifactSetPlan {
        if (selection.mode == ArtifactSetSelectionMode.NONE) return ArtifactSetPlan(emptyList())
        if (selection.mode == ArtifactSetSelectionMode.CUSTOM) {
            val requirements = selection.requirements.mapNotNull { target ->
                allArtifacts.firstOrNull {
                    GoodKeyNormalizer.normalize(it.setKey) == target.setKey
                }?.let {
                    ArtifactSetRequirement(
                        setKey = target.setKey,
                        setName = artifactCatalogService.setName(target.setKey) ?: it.setName,
                        count = target.count,
                    )
                }
            }
            return ArtifactSetPlan(requirements)
        }

        val counts = currentArtifacts
            .groupingBy { GoodKeyNormalizer.normalize(it.setKey) }
            .eachCount()
            .entries
            .sortedByDescending(Map.Entry<String, Int>::value)
        val requirements = when {
            counts.firstOrNull()?.value ?: 0 >= 4 -> listOf(
                setRequirement(counts.first().key, 4, currentArtifacts),
            )

            else -> counts.filter { it.value >= 2 }
                .take(2)
                .map { setRequirement(it.key, 2, currentArtifacts) }
        }
        return ArtifactSetPlan(requirements)
    }

    private fun setRequirement(
        setKey: String,
        count: Int,
        artifacts: List<PlayerArtifact>,
    ): ArtifactSetRequirement {
        val fallbackName = artifacts.first {
            GoodKeyNormalizer.normalize(it.setKey) == setKey
        }.setName
        return ArtifactSetRequirement(
            setKey = setKey,
            setName = artifactCatalogService.setName(setKey) ?: fallbackName,
            count = count,
        )
    }

    private fun candidateCanPreserveSetPlan(
        candidate: PlayerArtifact,
        currentBySlot: Map<String, ScoredArtifact>,
        setPlan: ArtifactSetPlan,
    ): Boolean {
        if (setPlan.requirements.isEmpty()) return true
        val replaced = currentBySlot[candidate.slotKey.lowercase()]?.artifact
        val counts = currentBySlot.values
            .map(ScoredArtifact::artifact)
            .toMutableList()
            .also {
                if (replaced != null) it.remove(replaced)
                it += candidate
            }
            .groupingBy { GoodKeyNormalizer.normalize(it.setKey) }
            .eachCount()
        return setPlan.requirements.all { (counts[it.setKey] ?: 0) >= it.count }
    }

    private fun satisfiesSetPlan(
        pieces: List<ScoredArtifact>,
        setPlan: ArtifactSetPlan,
    ): Boolean {
        val counts = pieces.groupingBy {
            GoodKeyNormalizer.normalize(it.artifact.setKey)
        }.eachCount()
        return setPlan.requirements.all { (counts[it.setKey] ?: 0) >= it.count }
    }

    private fun score(
        indexedArtifact: IndexedArtifact,
        strategy: ArtifactScoringStrategy,
        currentArtifact: Boolean,
        contextTotals: Map<String, Double> = emptyMap(),
    ): ScoredArtifact =
        scoredArtifact(
            indexedArtifact,
            cachedEvaluateArtifact(indexedArtifact.artifact, strategy, contextTotals),
            currentArtifact,
        )

    private fun scoredArtifact(
        indexedArtifact: IndexedArtifact,
        evaluation: ArtifactEvaluation,
        currentArtifact: Boolean,
    ): ScoredArtifact {
        val artifact = indexedArtifact.artifact
        return ScoredArtifact(
            inventoryIndex = indexedArtifact.index,
            artifact = artifact,
            setName = artifactCatalogService.setName(artifact.setKey) ?: artifact.setName,
            score = evaluation.score,
            grade = evaluation.grade,
            weightedRolls = evaluation.weightedRolls,
            mainStatFit = evaluation.mainStatFit,
            mainStatFitKey = mainStatFitKey(evaluation.mainStatFit),
            mainStatMessageKey = statMessageKey(artifact.mainStatKey),
            mainStatFormattedValue = formatStatValue(
                artifact.mainStatKey,
                artifactMainStatValue(artifact),
            ),
            stats = artifact.substats.map { stat ->
                val roll = evaluation.rollAnalysis.substats[stat.key]
                ScoredArtifactStat(
                    key = stat.key,
                    messageKey = statMessageKey(stat.key),
                    formattedValue = stat.formattedValue,
                    useful = roll?.useful == true,
                    rollCount = roll?.rollCount ?: 1,
                    tierSummary = roll?.tiers.orEmpty()
                        .sortedDescending()
                        .joinToString(" · ") { "T$it" },
                    rollQuality = roll?.quality ?: 0.0,
                )
            },
            rollQuality = evaluation.rollAnalysis.quality,
            usefulRollQuality = evaluation.rollAnalysis.usefulQuality,
            usefulRolls = evaluation.rollAnalysis.usefulRolls,
            totalRolls = evaluation.rollAnalysis.totalRolls,
            usefulUpgradeRolls = evaluation.rollAnalysis.usefulUpgradeRolls,
            upgradeRolls = evaluation.rollAnalysis.upgradeRolls,
            targetEfficiency = evaluation.substatEfficiency,
            currentArtifact = currentArtifact,
        )
    }

    private fun formatStatValue(key: String, value: Double): String {
        val rounded = java.math.BigDecimal.valueOf(value)
            .setScale(1, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        return if (key.endsWith("_")) "$rounded %" else rounded
    }

    private fun cachedEvaluateArtifact(
        artifact: PlayerArtifact,
        strategy: ArtifactScoringStrategy,
        contextTotals: Map<String, Double> = emptyMap(),
    ): ArtifactEvaluation {
        val cacheService = artifactEvaluationCacheService
            ?: return evaluateArtifact(artifact, strategy, contextTotals)
        val cacheKey = evaluationCacheKey(artifact, strategy, contextTotals)
        return cacheService.getOrCompute(cacheKey) {
            evaluateArtifact(artifact, strategy, contextTotals)
        }
    }

    private fun evaluateArtifact(
        artifact: PlayerArtifact,
        strategy: ArtifactScoringStrategy,
        contextTotals: Map<String, Double> = emptyMap(),
    ): ArtifactEvaluation {
        val rollAnalysis = analyzeArtifactRolls(artifact, strategy)
        val substatRolls = artifact.substats.sumOf { stat ->
            val maximum = MAX_SUBSTAT_ROLLS[stat.key] ?: return@sumOf 0.0
            val effectiveValue = strategy.effectiveValue(stat.key, stat.value, contextTotals)
            (effectiveValue / maximum) * (strategy.statWeights[stat.key] ?: 0.0)
        }
        val mainStatRolls = if (artifact.slotKey.lowercase() in VARIABLE_MAIN_STAT_SLOTS) {
            val maximum = MAIN_STAT_MAX_VALUES[artifact.rarity]?.get(artifact.mainStatKey)
            if (maximum == null || maximum <= 0.0) {
                0.0
            } else {
                strategy.effectiveValue(
                    artifact.mainStatKey,
                    artifactMainStatValue(artifact),
                    contextTotals,
                ) / maximum * MAIN_STAT_ROLL_EQUIVALENT *
                    strategy.mainStatWeight(artifact.slotKey.lowercase(), artifact.mainStatKey)
            }
        } else {
            0.0
        }
        val weightedRolls = substatRolls + mainStatRolls
        val mainStatFit = strategy.mainStatFit(
            artifact.slotKey.lowercase(),
            artifact.mainStatKey,
        )
        val bestSubstatWeight = strategy.statWeights.values.maxOrNull()
            ?.coerceAtLeast(BUILD_COMPARISON_EPSILON)
            ?: 1.0
        val substatEfficiency = if (rollAnalysis.totalRolls > 0) {
            (substatRolls / (rollAnalysis.totalRolls * bestSubstatWeight))
                .coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val variableMainStat = artifact.slotKey.lowercase() in VARIABLE_MAIN_STAT_SLOTS
        val mainStatMaximum = MAIN_STAT_MAX_VALUES[artifact.rarity]?.get(artifact.mainStatKey)
        val mainStatQuality = if (mainStatMaximum != null && mainStatMaximum > 0.0) {
            val effectiveMainStatValue = strategy.effectiveValue(
                artifact.mainStatKey,
                artifactMainStatValue(artifact),
                contextTotals,
            )
            (effectiveMainStatValue / mainStatMaximum).coerceIn(0.0, 1.0) * mainStatFit
        } else {
            mainStatFit
        }
        val score = if (variableMainStat) {
            (
                mainStatQuality * VARIABLE_MAIN_STAT_SCORE_SHARE +
                    substatEfficiency * (1.0 - VARIABLE_MAIN_STAT_SCORE_SHARE)
                ) * 100.0
        } else {
            substatEfficiency * 100.0
        }.coerceIn(0.0, 100.0)
        return ArtifactEvaluation(
            score = score,
            grade = grade(score),
            weightedRolls = weightedRolls,
            mainStatFit = mainStatFit,
            statWeights = strategy.statWeights,
            substatEfficiency = substatEfficiency,
            rollAnalysis = rollAnalysis,
        )
    }

    private fun analyzeArtifactRolls(
        artifact: PlayerArtifact,
        strategy: ArtifactScoringStrategy,
    ): ArtifactRollAnalysis {
        if (artifact.substats.isEmpty()) {
            return ArtifactRollAnalysis(
                totalRolls = 0,
                quality = 0.0,
                usefulQuality = 0.0,
                usefulRolls = 0,
                usefulRollRate = 0.0,
                upgradeRolls = 0,
                usefulUpgradeRolls = 0,
                upgradeHitRate = null,
                substats = emptyMap(),
            )
        }
        val maximumRollCount = (1 + artifact.level.coerceIn(0, 20) / 4).coerceIn(1, 6)
        val candidatesByStat = artifact.substats.map { stat ->
            rollCandidates(stat, maximumRollCount)
        }
        val selected = selectRollCandidates(
            candidatesByStat,
            artifact.totalRolls?.takeIf { it >= artifact.substats.size },
        )
        val analyses = artifact.substats.zip(selected).associate { (stat, candidate) ->
            val values = SUBSTAT_ROLL_VALUES[stat.key].orEmpty()
            val maximum = values.lastOrNull() ?: 1.0
            val quality = candidate.tiers
                .sumOf { tier -> values.getOrElse(tier - 1) { maximum } / maximum } /
                candidate.tiers.size.coerceAtLeast(1)
            val useful = strategy.isWantedStat(stat.key)
            stat.key to ArtifactSubstatRollAnalysis(
                key = stat.key,
                rollCount = candidate.tiers.size,
                tiers = candidate.tiers,
                quality = quality.coerceIn(0.0, 1.0),
                useful = useful,
            )
        }
        val totalRolls = analyses.values.sumOf(ArtifactSubstatRollAnalysis::rollCount)
        val usefulAnalyses = analyses.values.filter(ArtifactSubstatRollAnalysis::useful)
        val usefulRolls = usefulAnalyses.sumOf(ArtifactSubstatRollAnalysis::rollCount)
        val upgradeRolls = (totalRolls - artifact.substats.size).coerceAtLeast(0)
        val usefulUpgradeRolls = usefulAnalyses.sumOf {
            (it.rollCount - 1).coerceAtLeast(0)
        }
        val allTierQuality = analyses.values.sumOf { analysis ->
            analysis.quality * analysis.rollCount
        }
        val usefulTierQuality = usefulAnalyses.sumOf { analysis ->
            analysis.quality * analysis.rollCount
        }
        return ArtifactRollAnalysis(
            totalRolls = totalRolls,
            quality = if (totalRolls > 0) allTierQuality / totalRolls else 0.0,
            usefulQuality = if (usefulRolls > 0) usefulTierQuality / usefulRolls else 0.0,
            usefulRolls = usefulRolls,
            usefulRollRate = if (totalRolls > 0) usefulRolls.toDouble() / totalRolls else 0.0,
            upgradeRolls = upgradeRolls,
            usefulUpgradeRolls = usefulUpgradeRolls,
            upgradeHitRate = if (upgradeRolls > 0) {
                usefulUpgradeRolls.toDouble() / upgradeRolls
            } else {
                null
            },
            substats = analyses,
        )
    }

    private fun rollCandidates(
        stat: PlayerArtifactStat,
        maximumRollCount: Int,
    ): List<RollCandidate> {
        val values = SUBSTAT_ROLL_VALUES[stat.key]
            ?: return listOf(RollCandidate(listOf(4), 0.0))
        val maximum = values.last()
        return (1..maximumRollCount).map { rollCount ->
            var best: RollCandidate? = null

            fun choose(
                remaining: Int,
                minimumTierIndex: Int,
                sum: Double,
                tiers: MutableList<Int>,
            ) {
                if (remaining == 0) {
                    val candidate = RollCandidate(
                        tiers = tiers.toList(),
                        error = abs(sum - stat.value) / maximum,
                    )
                    if (best == null || candidate.error < requireNotNull(best).error) {
                        best = candidate
                    }
                    return
                }
                for (tierIndex in minimumTierIndex..values.lastIndex) {
                    tiers += tierIndex + 1
                    choose(
                        remaining - 1,
                        tierIndex,
                        sum + values[tierIndex],
                        tiers,
                    )
                    tiers.removeLast()
                }
            }

            choose(rollCount, 0, 0.0, mutableListOf())
            requireNotNull(best)
        }
    }

    private fun selectRollCandidates(
        candidatesByStat: List<List<RollCandidate>>,
        requestedTotalRolls: Int?,
    ): List<RollCandidate> {
        if (requestedTotalRolls == null) {
            return candidatesByStat.map { candidates ->
                candidates.minBy(RollCandidate::error)
            }
        }
        var states = mapOf(0 to RollSelection(0.0, emptyList()))
        candidatesByStat.forEach { candidates ->
            val next = mutableMapOf<Int, RollSelection>()
            states.forEach { (total, selection) ->
                candidates.forEach { candidate ->
                    val candidateTotal = total + candidate.tiers.size
                    if (candidateTotal <= requestedTotalRolls) {
                        val combined = RollSelection(
                            error = selection.error + candidate.error,
                            candidates = selection.candidates + candidate,
                        )
                        val existing = next[candidateTotal]
                        if (existing == null || combined.error < existing.error) {
                            next[candidateTotal] = combined
                        }
                    }
                }
            }
            states = next
        }
        return states[requestedTotalRolls]?.candidates
            ?: states.minByOrNull { (total, selection) ->
                abs(total - requestedTotalRolls) * 100.0 + selection.error
            }?.value?.candidates
            ?: candidatesByStat.map { it.minBy(RollCandidate::error) }
    }

    private fun optimisticScore(
        artifact: PlayerArtifact,
        strategy: ArtifactScoringStrategy,
        contextTotals: Map<String, Double> = emptyMap(),
    ): Double = evaluateArtifact(
        optimisticallyFinishArtifact(artifact, strategy, contextTotals),
        strategy,
        contextTotals,
    ).score

    private fun optimisticallyFinishArtifact(
        artifact: PlayerArtifact,
        strategy: ArtifactScoringStrategy,
        contextTotals: Map<String, Double>,
    ): PlayerArtifact {
        val remaining = remainingUpgradeMilestones(artifact)
        val stats = artifact.substats.associateTo(linkedMapOf()) { it.key to it.value }
        repeat(remaining) { step ->
            val availableKeys = if (stats.size < 4) {
                SUBSTAT_WEIGHTS.keys.filter { it != artifact.mainStatKey && it !in stats }
            } else {
                stats.keys.toList()
            }
            val bestKey = availableKeys.maxByOrNull { key ->
                val tentativeStats = stats.toMutableMap().also {
                    it[key] = it.getOrDefault(key, 0.0) + MAX_SUBSTAT_ROLLS.getValue(key)
                }
                evaluateArtifact(
                    artifact.copy(
                        level = 20,
                        substats = tentativeStats.map { (statKey, value) ->
                            PlayerArtifactStat(statKey, value)
                        },
                        totalRolls = artifact.totalRolls?.plus(step + 1),
                    ),
                    strategy,
                    contextTotals,
                ).score
            } ?: return@repeat
            stats[bestKey] = stats.getOrDefault(bestKey, 0.0) +
                MAX_SUBSTAT_ROLLS.getValue(bestKey)
        }
        return artifact.copy(
            level = 20,
            substats = stats.map { (key, value) -> PlayerArtifactStat(key, value) },
            totalRolls = artifact.totalRolls?.plus(remaining),
        )
    }

    private fun simulateRemainingLevels(
        artifact: PlayerArtifact,
        random: Random,
        strategy: ArtifactScoringStrategy,
    ): LevelingSimulationOutcome {
        val stats = artifact.substats.associateTo(linkedMapOf()) { it.key to it.value }
        val remaining = remainingUpgradeMilestones(artifact)
        var usefulUpgrades = 0
        repeat(remaining) {
            if (stats.size < 4) {
                val newKey = randomSubstatKey(
                    mainStatKey = artifact.mainStatKey,
                    excludedKeys = stats.keys,
                    random = random,
                )
                stats[newKey] = randomSubstatRoll(newKey, random)
                if (strategy.isWantedStat(newKey)) usefulUpgrades++
            } else {
                val key = stats.keys.random(random)
                stats[key] = stats.getValue(key) + randomSubstatRoll(key, random)
                if (strategy.isWantedStat(key)) usefulUpgrades++
            }
        }
        return LevelingSimulationOutcome(
            artifact = artifact.copy(
                level = 20,
                substats = stats.map { (key, value) -> PlayerArtifactStat(key, value) },
                totalRolls = artifact.totalRolls?.plus(remaining),
            ),
            usefulUpgrades = usefulUpgrades,
        )
    }

    private fun nextUsefulUpgradeChance(
        artifact: PlayerArtifact,
        strategy: ArtifactScoringStrategy,
    ): Double {
        if (remainingUpgradeMilestones(artifact) <= 0) return 0.0
        val existingKeys = artifact.substats.mapTo(mutableSetOf(), PlayerArtifactStat::key)
        if (existingKeys.size >= 4) {
            return existingKeys.count(strategy::isWantedStat).toDouble() / existingKeys.size
        }
        val options = SUBSTAT_WEIGHTS.filterKeys {
            it != artifact.mainStatKey && it !in existingKeys
        }
        val totalWeight = options.values.sum()
        if (totalWeight <= 0.0) return 0.0
        return options.filterKeys(strategy::isWantedStat).values.sum() / totalWeight
    }

    private fun percentile(sortedValues: DoubleArray, percentile: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val index = ((sortedValues.lastIndex) * percentile)
            .toInt()
            .coerceIn(sortedValues.indices)
        return sortedValues[index]
    }

    private fun generateDomainArtifact(
        setKey: String,
        random: Random,
    ): PlayerArtifact {
        val slotKey = SLOTS.random(random)
        val mainStatKey = weightedChoice(MAIN_STATS_BY_SLOT.getValue(slotKey), random)
        val initialSubstatCount = if (random.nextDouble() < FOUR_SUBSTAT_CHANCE) 4 else 3
        val stats = linkedMapOf<String, Double>()
        repeat(initialSubstatCount) {
            val key = randomSubstatKey(mainStatKey, stats.keys, random)
            stats[key] = randomSubstatRoll(key, random)
        }
        repeat(5) {
            if (stats.size < 4) {
                val key = randomSubstatKey(mainStatKey, stats.keys, random)
                stats[key] = randomSubstatRoll(key, random)
            } else {
                val key = stats.keys.random(random)
                stats[key] = stats.getValue(key) + randomSubstatRoll(key, random)
            }
        }
        return PlayerArtifact(
            setKey = setKey,
            slotKey = slotKey,
            level = 20,
            rarity = 5,
            mainStatKey = mainStatKey,
            location = null,
            locked = false,
            substats = stats.map { (key, value) -> PlayerArtifactStat(key, value) },
            totalRolls = initialSubstatCount + 5,
            astralMark = false,
            elixirCrafted = false,
        )
    }

    private fun randomSubstatKey(
        mainStatKey: String,
        excludedKeys: Collection<String>,
        random: Random,
    ): String {
        val options = SUBSTAT_WEIGHTS
            .filterKeys { it != mainStatKey && it !in excludedKeys }
            .map { it.key to it.value }
        return weightedChoice(options, random)
    }

    private fun randomSubstatRoll(key: String, random: Random): Double =
        SUBSTAT_ROLL_VALUES.getValue(key).random(random)

    private fun <T> weightedChoice(
        options: List<Pair<T, Double>>,
        random: Random,
    ): T {
        val totalWeight = options.sumOf(Pair<T, Double>::second)
        var roll = random.nextDouble() * totalWeight
        options.forEach { (value, weight) ->
            roll -= weight
            if (roll <= 0.0) return value
        }
        return options.last().first
    }

    private fun remainingUpgradeMilestones(artifact: PlayerArtifact): Int =
        (5 - artifact.level.coerceIn(0, 20) / 4).coerceAtLeast(0)

    private fun averageLoadoutScore(pieces: List<ScoredArtifact>): Double =
        pieces.sumOf(ScoredArtifact::score) / SLOTS.size

    private fun probabilityAtLeastOne(chance: Double, attempts: Int): Double =
        1.0 - (1.0 - chance).pow(attempts)

    private fun grade(score: Double): String = when {
        score >= 72.0 -> "S"
        score >= 57.0 -> "A"
        score >= 42.0 -> "B"
        score >= 27.0 -> "C"
        else -> "D"
    }

    private fun mainStatFitKey(fit: Double): String = when {
        fit >= 0.95 -> "optimizer.fit.preferred"
        fit >= 0.70 -> "optimizer.fit.viable"
        else -> "optimizer.fit.mismatch"
    }

    private fun stableSeed(vararg values: Any?): Int =
        values.fold(17) { result, value -> 31 * result + value.hashCode() }

    private fun evaluationCacheKey(
        artifact: PlayerArtifact,
        strategy: ArtifactScoringStrategy,
        contextTotals: Map<String, Double>,
    ): String {
        val rawKey = buildString {
            append("v=").append(ARTIFACT_EVALUATION_CACHE_VERSION)
            append("|profile=").append(strategy.profile.key)
            append("|strategy=").append(strategy.seedKey)
            append("|artifact=")
            append(artifact.setKey).append(':')
            append(artifact.slotKey.lowercase()).append(':')
            append(artifact.level).append(':')
            append(artifact.rarity).append(':')
            append(artifact.mainStatKey).append(':')
            append(artifact.totalRolls ?: "auto")
            artifact.substats
                .sortedBy(PlayerArtifactStat::key)
                .forEach { stat ->
                    append('|')
                    append(stat.key)
                    append('=')
                    append(fingerprintNumber(stat.value))
                }
            append("|context=")
            contextTotals
                .filterValues(Double::isFinite)
                .toSortedMap()
                .forEach { (key, value) ->
                    append(key)
                    append('=')
                    append(fingerprintNumber(value))
                    append(',')
                }
        }
        return sha256(rawKey)
    }

    private fun fingerprintNumber(value: Double): String =
        BigDecimal.valueOf(value)
            .setScale(6, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX_CHARS[unsigned ushr 4])
                append(HEX_CHARS[unsigned and 0x0f])
            }
        }
    }

    private data class IndexedArtifact(
        val index: Int,
        val artifact: PlayerArtifact,
    )

    private data class PreLevelingCandidate(
        val indexedArtifact: IndexedArtifact,
        val target: ScoredArtifact?,
        val optimisticScore: Double,
    )

    private data class RollCandidate(
        val tiers: List<Int>,
        val error: Double,
    )

    private data class RollSelection(
        val error: Double,
        val candidates: List<RollCandidate>,
    )

    private data class LevelingSimulationOutcome(
        val artifact: PlayerArtifact,
        val usefulUpgrades: Int,
    )

    private data class LoadoutCandidate(
        val pieces: List<ScoredArtifact>,
        val buildStats: ArtifactBuildStats,
        val pieceScore: Double,
    )

    private data class ArtifactScoringStrategy(
        val profile: ArtifactOptimizationProfile,
        val targets: ArtifactOptimizationTargets,
    ) {
        val statWeights: Map<String, Double> =
            if (targets.custom) {
                targets.substatPriorities.mapIndexed { index, key ->
                    key to PRIORITY_WEIGHT_DECAY.pow(index)
                }.toMap().toMutableMap().also { weights ->
                    targets.minimumTargets.keys.forEach {
                        weights[it] = maxOf(weights[it] ?: 0.0, MINIMUM_TARGET_WEIGHT)
                    }
                }
            } else {
                profile.statWeights
            }

        val seedKey: String = buildString {
            append(profile.key)
            targets.mainStats.toSortedMap().forEach { (slot, stat) ->
                append('|').append(slot).append('=').append(stat ?: "auto")
            }
            append('|')
            append(targets.substatPriorities.joinToString(","))
            append('|')
            targets.minimumTargets.toSortedMap().forEach { (key, value) ->
                append(key).append(">=").append(value).append(',')
            }
            append('|')
            targets.maximumTargets.toSortedMap().forEach { (key, value) ->
                append(key).append("<=").append(value).append(',')
            }
            append("|bonuses=")
            targets.additionalStats.toSortedMap().forEach { (key, value) ->
                append(key).append('=').append(value).append(',')
            }
        }

        fun mainStatFit(slotKey: String, mainStatKey: String): Double {
            val explicitTarget = targets.mainStats[slotKey]
            val baseFit = if (targets.custom && explicitTarget != null) {
                if (mainStatKey == explicitTarget) 1.0 else CUSTOM_MAIN_STAT_MISMATCH
            } else {
                profile.mainStatFit(slotKey, mainStatKey)
            }
            if (targets.custom && explicitTarget != null) return baseFit
            if (slotKey == "circlet" &&
                mainStatKey !in CRIT_STAT_KEYS &&
                isCritOriented()
            ) {
                return minOf(baseFit, NON_CRIT_CIRCLET_DAMAGE_FIT)
            }
            return baseFit
        }

        fun mainStatWeight(slotKey: String, mainStatKey: String): Double {
            val explicitTarget = targets.mainStats[slotKey]
            return when {
                targets.custom && explicitTarget == mainStatKey -> 1.0
                targets.custom && explicitTarget != null -> CUSTOM_MAIN_STAT_MISMATCH
                else -> maxOf(
                    statWeights[mainStatKey] ?: 0.0,
                    mainStatFit(slotKey, mainStatKey),
                )
            }
        }

        fun effectiveValue(
            statKey: String,
            value: Double,
            contextTotals: Map<String, Double>,
        ): Double {
            val cappedValue = targets.maximumTargets[statKey]?.let { maximum ->
                val context = contextTotals.getOrDefault(statKey, 0.0)
                minOf(value, (maximum - context).coerceAtLeast(0.0))
            } ?: value
            return when {
                statKey == "critRate_" ->
                    effectiveCritRateValue(cappedValue, contextTotals)
                statKey == "critDMG_" ->
                    effectiveCritDamageValue(cappedValue, contextTotals)
                else -> cappedValue
            }
        }

        fun acceptsExplicitMainStat(slotKey: String, mainStatKey: String): Boolean {
            val explicitTarget = targets.mainStats[slotKey]
            return !targets.custom || explicitTarget == null || mainStatKey == explicitTarget
        }

        fun isWantedStat(statKey: String): Boolean =
            if (targets.custom) {
                statKey in targets.substatPriorities || statKey in targets.minimumTargets
            } else {
                (statWeights[statKey] ?: 0.0) >= USEFUL_STAT_WEIGHT
            }

        private fun isCritOriented(): Boolean =
            CRIT_STAT_KEYS.any { key ->
                key in targets.substatPriorities ||
                    key in targets.minimumTargets ||
                    (statWeights[key] ?: 0.0) >= CRIT_ORIENTED_STAT_WEIGHT
            }

        private fun effectiveCritRateValue(
            value: Double,
            contextTotals: Map<String, Double>,
        ): Double {
            val maximum = targets.maximumTargets["critRate_"] ?: CRIT_RATE_CAP
            if (value <= 0.0 || maximum <= 0.0) return 0.0
            val start = contextTotals.getOrDefault("critRate_", 0.0)
                .coerceIn(0.0, maximum)
            val end = (start + value).coerceAtMost(maximum)
            val usableValue = (end - start).coerceAtLeast(0.0)
            if (usableValue <= 0.0) return 0.0

            val critDamage = contextTotals.getOrDefault("critDMG_", 0.0)
                .coerceAtLeast(0.0)
            val ratioFactor = (critDamage / (CRIT_DAMAGE_RATIO * maximum))
                .coerceIn(CRIT_RATIO_MIN_FACTOR, CRIT_RATIO_MAX_FACTOR)
            val averageCritRate = (start + end) / 2.0
            return usableValue * ratioFactor * critRateCapFactor(averageCritRate, maximum)
        }

        private fun effectiveCritDamageValue(
            value: Double,
            contextTotals: Map<String, Double>,
        ): Double {
            if (value <= 0.0) return 0.0
            val critRateTarget = targets.maximumTargets["critRate_"] ?: CRIT_RATE_CAP
            val critRate = contextTotals.getOrDefault("critRate_", 0.0)
                .coerceIn(0.0, critRateTarget)
            val ratioFactor = (critRate / critRateTarget)
                .coerceIn(CRIT_RATIO_MIN_FACTOR, 1.0)
            return value * ratioFactor
        }

        private fun critRateCapFactor(
            averageCritRate: Double,
            maximum: Double,
        ): Double {
            val softCap = maximum * CRIT_RATE_SOFT_CAP_SHARE
            if (averageCritRate <= softCap) return 1.0
            val progress = ((averageCritRate - softCap) / (maximum - softCap))
                .coerceIn(0.0, 1.0)
            return 1.0 - progress * (1.0 - CRIT_RATE_MIN_CAP_FACTOR)
        }
    }

    companion object {
        private val SLOTS = listOf("flower", "plume", "sands", "goblet", "circlet")
        private val VARIABLE_MAIN_STAT_SLOTS = listOf("sands", "goblet", "circlet")
        private val SLOT_ORDER = SLOTS.withIndex().associate { (index, slot) -> slot to index }
        private const val DOMAIN_SET_CHANCE = 0.5
        private const val FOUR_SUBSTAT_CHANCE = 0.2
        private const val FARMING_SIMULATIONS = 12_000
        private const val LEVELING_SIMULATIONS = 800
        private const val LEVELING_PREFILTER_LIMIT = 48
        private const val LEVELING_RESULTS_PER_ARTIFACT = 3
        private const val ARTIFACT_FARMING_SIMULATIONS = 6_000
        private const val MINIMUM_LEVELING_CHANCE = 0.30
        private const val MINIMUM_LOADOUT_LEVEL = 16
        private const val LOADOUT_BEAM_WIDTH = 320
        private const val SCORE_EPSILON = 0.5
        private const val BUILD_COMPARISON_EPSILON = 0.01
        private const val MAIN_STAT_ROLL_EQUIVALENT = 8.0
        private const val VARIABLE_MAIN_STAT_SCORE_SHARE = 0.8
        private const val USEFUL_STAT_WEIGHT = 0.4
        private const val CRIT_ORIENTED_STAT_WEIGHT = 0.6
        private const val CUSTOM_MAIN_STAT_MISMATCH = 0.2
        private const val NON_CRIT_CIRCLET_DAMAGE_FIT = 0.25
        private const val PRIORITY_WEIGHT_DECAY = 0.85
        private const val MINIMUM_TARGET_WEIGHT = 1.15
        private const val CRIT_RATE_CAP = 100.0
        private const val CRIT_DAMAGE_RATIO = 2.0
        private const val CRIT_RATIO_MIN_FACTOR = 0.25
        private const val CRIT_RATIO_MAX_FACTOR = 1.25
        private const val CRIT_RATE_SOFT_CAP_SHARE = 0.85
        private const val CRIT_RATE_MIN_CAP_FACTOR = 0.25
        private const val MAX_TARGET_VALUE = 100_000.0
        private const val ARTIFACT_EVALUATION_CACHE_VERSION = 2
        private const val HEX_CHARS = "0123456789abcdef"
        private val CRIT_STAT_KEYS = setOf("critRate_", "critDMG_")

        private val SUBSTAT_ROLL_VALUES = mapOf(
            "hp" to listOf(209.13, 239.0, 268.88, 298.75),
            "hp_" to listOf(4.08, 4.66, 5.25, 5.83),
            "atk" to listOf(13.62, 15.56, 17.51, 19.45),
            "atk_" to listOf(4.08, 4.66, 5.25, 5.83),
            "def" to listOf(16.2, 18.52, 20.83, 23.15),
            "def_" to listOf(5.1, 5.83, 6.56, 7.29),
            "critRate_" to listOf(2.72, 3.11, 3.5, 3.89),
            "critDMG_" to listOf(5.44, 6.22, 6.99, 7.77),
            "enerRech_" to listOf(4.53, 5.18, 5.83, 6.48),
            "eleMas" to listOf(16.32, 18.65, 20.98, 23.31),
        )
        private val MAX_SUBSTAT_ROLLS =
            SUBSTAT_ROLL_VALUES.mapValues { (_, values) -> values.last() }

        private val SUBSTAT_WEIGHTS = mapOf(
            "hp" to 6.0,
            "atk" to 6.0,
            "def" to 6.0,
            "hp_" to 4.0,
            "atk_" to 4.0,
            "def_" to 4.0,
            "enerRech_" to 4.0,
            "eleMas" to 4.0,
            "critRate_" to 3.0,
            "critDMG_" to 3.0,
        )

        private val MAIN_STATS_BY_SLOT = mapOf(
            "flower" to listOf("hp" to 1.0),
            "plume" to listOf("atk" to 1.0),
            "sands" to listOf(
                "hp_" to 26.68,
                "atk_" to 26.66,
                "def_" to 26.66,
                "enerRech_" to 10.0,
                "eleMas" to 10.0,
            ),
            "goblet" to listOf(
                "hp_" to 19.25,
                "atk_" to 19.25,
                "def_" to 19.0,
                "pyro_dmg_" to 5.0,
                "electro_dmg_" to 5.0,
                "cryo_dmg_" to 5.0,
                "hydro_dmg_" to 5.0,
                "dendro_dmg_" to 5.0,
                "anemo_dmg_" to 5.0,
                "geo_dmg_" to 5.0,
                "physical_dmg_" to 5.0,
                "eleMas" to 2.5,
            ),
            "circlet" to listOf(
                "hp_" to 22.0,
                "atk_" to 22.0,
                "def_" to 22.0,
                "critRate_" to 10.0,
                "critDMG_" to 10.0,
                "heal_" to 10.0,
                "eleMas" to 4.0,
            ),
        )

        private val CORE_BUILD_STATS =
            listOf("critRate_", "critDMG_", "enerRech_", "eleMas")

        private val MANUAL_BONUS_STAT_KEYS = listOf(
            "hp",
            "atk",
            "def",
            "hp_",
            "atk_",
            "def_",
            "critRate_",
            "critDMG_",
            "enerRech_",
            "eleMas",
            "heal_",
            "physical_dmg_",
            "pyro_dmg_",
            "hydro_dmg_",
            "electro_dmg_",
            "cryo_dmg_",
            "anemo_dmg_",
            "geo_dmg_",
            "dendro_dmg_",
        )
        private val OPTIMIZER_BONUS_STAT_KEYS =
            listOf("critRate_", "critDMG_", "enerRech_")

        private val FIVE_STAR_MAIN_STAT_FACTORS = listOf(
            7.0, 9.0, 10.9, 12.9, 14.9, 16.9, 18.9,
            20.9, 22.8, 24.8, 26.8, 28.8, 30.8, 32.8,
            34.8, 36.7, 38.7, 40.7, 42.7, 44.7, 46.6,
        ).map { it / 46.6 }

        private val MAIN_STAT_MAX_VALUES = mapOf(
            5 to mapOf(
                "hp" to 4_780.0,
                "atk" to 311.0,
                "hp_" to 46.6,
                "atk_" to 46.6,
                "def_" to 58.3,
                "enerRech_" to 51.8,
                "eleMas" to 187.0,
                "critRate_" to 31.1,
                "critDMG_" to 62.2,
                "heal_" to 35.9,
                "physical_dmg_" to 58.3,
                "pyro_dmg_" to 46.6,
                "hydro_dmg_" to 46.6,
                "electro_dmg_" to 46.6,
                "cryo_dmg_" to 46.6,
                "anemo_dmg_" to 46.6,
                "geo_dmg_" to 46.6,
                "dendro_dmg_" to 46.6,
            ),
            4 to mapOf(
                "hp" to 3_571.0,
                "atk" to 232.0,
                "hp_" to 34.8,
                "atk_" to 34.8,
                "def_" to 43.5,
                "enerRech_" to 38.7,
                "eleMas" to 139.0,
                "critRate_" to 23.2,
                "critDMG_" to 46.4,
                "heal_" to 26.8,
                "physical_dmg_" to 43.5,
                "pyro_dmg_" to 34.8,
                "hydro_dmg_" to 34.8,
                "electro_dmg_" to 34.8,
                "cryo_dmg_" to 34.8,
                "anemo_dmg_" to 34.8,
                "geo_dmg_" to 34.8,
                "dendro_dmg_" to 34.8,
            ),
        )

        fun statMessageKey(key: String): String = when (key) {
            "hp" -> "artifact.stat.hp"
            "hp_" -> "artifact.stat.hpPercent"
            "atk" -> "artifact.stat.atk"
            "atk_" -> "artifact.stat.atkPercent"
            "def" -> "artifact.stat.def"
            "def_" -> "artifact.stat.defPercent"
            "critRate_" -> "artifact.stat.critRate"
            "critDMG_" -> "artifact.stat.critDamage"
            "enerRech_" -> "artifact.stat.energyRecharge"
            "eleMas" -> "artifact.stat.elementalMastery"
            "heal_" -> "artifact.stat.healingBonus"
            "physical_dmg_" -> "artifact.stat.physicalDamage"
            "pyro_dmg_" -> "artifact.stat.pyroDamage"
            "hydro_dmg_" -> "artifact.stat.hydroDamage"
            "electro_dmg_" -> "artifact.stat.electroDamage"
            "cryo_dmg_" -> "artifact.stat.cryoDamage"
            "anemo_dmg_" -> "artifact.stat.anemoDamage"
            "geo_dmg_" -> "artifact.stat.geoDamage"
            "dendro_dmg_" -> "artifact.stat.dendroDamage"
            else -> "artifact.stat.other"
        }
    }
}

enum class ArtifactOptimizationProfile(
    val key: String,
    val messageKey: String,
    val descriptionKey: String,
    val statWeights: Map<String, Double>,
) {
    ATTACK(
        key = "attack",
        messageKey = "optimizer.profile.attack",
        descriptionKey = "optimizer.profile.attack.description",
        statWeights = mapOf(
            "critRate_" to 1.0,
            "critDMG_" to 1.0,
            "atk_" to 0.8,
            "enerRech_" to 0.55,
            "eleMas" to 0.45,
            "atk" to 0.2,
        ),
    ),
    HP(
        key = "hp",
        messageKey = "optimizer.profile.hp",
        descriptionKey = "optimizer.profile.hp.description",
        statWeights = mapOf(
            "critRate_" to 1.0,
            "critDMG_" to 1.0,
            "hp_" to 0.8,
            "enerRech_" to 0.55,
            "eleMas" to 0.4,
            "hp" to 0.2,
        ),
    ),
    DEFENSE(
        key = "defense",
        messageKey = "optimizer.profile.defense",
        descriptionKey = "optimizer.profile.defense.description",
        statWeights = mapOf(
            "critRate_" to 1.0,
            "critDMG_" to 1.0,
            "def_" to 0.8,
            "enerRech_" to 0.55,
            "def" to 0.2,
        ),
    ),
    REACTION(
        key = "reaction",
        messageKey = "optimizer.profile.reaction",
        descriptionKey = "optimizer.profile.reaction.description",
        statWeights = mapOf(
            "eleMas" to 1.0,
            "enerRech_" to 0.75,
            "critRate_" to 0.35,
            "critDMG_" to 0.35,
            "atk_" to 0.25,
        ),
    ),
    ENERGY_SUPPORT(
        key = "energy",
        messageKey = "optimizer.profile.energy",
        descriptionKey = "optimizer.profile.energy.description",
        statWeights = mapOf(
            "enerRech_" to 1.0,
            "critRate_" to 0.65,
            "hp_" to 0.45,
            "atk_" to 0.45,
            "def_" to 0.45,
            "eleMas" to 0.4,
        ),
    ),
    ;

    fun mainStatFit(slotKey: String, mainStatKey: String): Double {
        if (slotKey == "flower" || slotKey == "plume") return 1.0
        val damageBonus = mainStatKey.endsWith("_dmg_")
        return when (this) {
            ATTACK -> when (slotKey) {
                "sands" -> when (mainStatKey) {
                    "atk_" -> 1.0
                    "enerRech_" -> 0.88
                    "eleMas" -> 0.78
                    else -> 0.42
                }
                "goblet" -> when {
                    damageBonus -> 1.0
                    mainStatKey == "atk_" -> 0.82
                    mainStatKey == "eleMas" -> 0.75
                    else -> 0.42
                }
                "circlet" -> when (mainStatKey) {
                    "critRate_", "critDMG_" -> 1.0
                    "atk_" -> 0.82
                    "eleMas" -> 0.7
                    else -> 0.42
                }
                else -> 1.0
            }

            HP -> when (slotKey) {
                "sands" -> when (mainStatKey) {
                    "hp_" -> 1.0
                    "enerRech_" -> 0.88
                    "eleMas" -> 0.7
                    else -> 0.42
                }
                "goblet" -> when {
                    damageBonus -> 1.0
                    mainStatKey == "hp_" -> 0.88
                    mainStatKey == "eleMas" -> 0.7
                    else -> 0.42
                }
                "circlet" -> when (mainStatKey) {
                    "critRate_", "critDMG_" -> 1.0
                    "hp_" -> 0.85
                    "heal_" -> 0.78
                    else -> 0.42
                }
                else -> 1.0
            }

            DEFENSE -> when (slotKey) {
                "sands" -> when (mainStatKey) {
                    "def_" -> 1.0
                    "enerRech_" -> 0.88
                    else -> 0.42
                }
                "goblet" -> when {
                    damageBonus -> 1.0
                    mainStatKey == "def_" -> 0.88
                    else -> 0.42
                }
                "circlet" -> when (mainStatKey) {
                    "critRate_", "critDMG_" -> 1.0
                    "def_" -> 0.85
                    else -> 0.42
                }
                else -> 1.0
            }

            REACTION -> when (slotKey) {
                "sands" -> when (mainStatKey) {
                    "eleMas" -> 1.0
                    "enerRech_" -> 0.9
                    else -> 0.48
                }
                "goblet" -> when {
                    mainStatKey == "eleMas" -> 1.0
                    damageBonus -> 0.82
                    else -> 0.48
                }
                "circlet" -> when (mainStatKey) {
                    "eleMas" -> 1.0
                    "critRate_", "critDMG_" -> 0.65
                    else -> 0.48
                }
                else -> 1.0
            }

            ENERGY_SUPPORT -> when (slotKey) {
                "sands" -> when (mainStatKey) {
                    "enerRech_" -> 1.0
                    "hp_", "atk_", "def_", "eleMas" -> 0.75
                    else -> 0.5
                }
                "goblet" -> when {
                    mainStatKey == "eleMas" -> 0.85
                    damageBonus || mainStatKey in setOf("hp_", "atk_", "def_") -> 0.75
                    else -> 0.5
                }
                "circlet" -> when (mainStatKey) {
                    "critRate_" -> 0.95
                    "heal_" -> 0.88
                    "critDMG_" -> 0.65
                    "hp_", "atk_", "def_", "eleMas" -> 0.72
                    else -> 0.5
                }
                else -> 1.0
            }
        }
    }

    companion object {
        fun fromKey(key: String?): ArtifactOptimizationProfile =
            entries.find { it.key == key } ?: ATTACK
    }
}

data class ArtifactOptimizationResult(
    val profile: ArtifactOptimizationProfile,
    val targets: ArtifactOptimizationTargets,
    val currentPieces: List<ScoredArtifact>,
    val currentAverageScore: Double,
    val currentGrade: String,
    val currentBuildStats: ArtifactBuildStats,
    val setPlan: ArtifactSetPlan,
    val farmingOutlooks: List<ArtifactFarmingOutlook>,
    val artifactOutlooks: List<ArtifactUpgradeOutlook>,
    val levelingCandidates: List<ArtifactLevelingCandidate>,
    val levelingRecommendations: List<ArtifactLevelingRecommendation>,
    val loadout: ArtifactLoadoutSuggestion?,
) {
    val displayedArtifacts: List<PlayerArtifact>
        get() = (
            currentPieces.map(ScoredArtifact::artifact) +
                levelingCandidates.map { it.piece.artifact } +
                loadout?.pieces.orEmpty().map(ScoredArtifact::artifact)
            ).distinct()
}

data class ArtifactEvaluation(
    val score: Double,
    val grade: String,
    val weightedRolls: Double,
    val mainStatFit: Double,
    val statWeights: Map<String, Double>,
    val substatEfficiency: Double,
    val rollAnalysis: ArtifactRollAnalysis,
)

data class ArtifactRollAnalysis(
    val totalRolls: Int,
    val quality: Double,
    val usefulQuality: Double,
    val usefulRolls: Int,
    val usefulRollRate: Double,
    val upgradeRolls: Int,
    val usefulUpgradeRolls: Int,
    val upgradeHitRate: Double?,
    val substats: Map<String, ArtifactSubstatRollAnalysis>,
)

data class ArtifactSubstatRollAnalysis(
    val key: String,
    val rollCount: Int,
    val tiers: List<Int>,
    val quality: Double,
    val useful: Boolean,
)

data class ArtifactOptimizationTargets(
    val custom: Boolean,
    val mainStats: Map<String, String?>,
    val substatPriorities: List<String>,
    val minimumTargets: Map<String, Double> = emptyMap(),
    val maximumTargets: Map<String, Double> = mapOf("critRate_" to 100.0),
    val additionalStats: Map<String, Double> = emptyMap(),
) {
    val substatKeys: Set<String>
        get() = substatPriorities.toSet()

    val additionalCritRate: Double
        get() = additionalStats.getOrDefault("critRate_", 0.0)

    companion object {
        fun defaults(profile: ArtifactOptimizationProfile): ArtifactOptimizationTargets =
            ArtifactOptimizationTargets(
                custom = false,
                mainStats = emptyMap(),
                substatPriorities = defaultPriorities(profile),
                maximumTargets = mapOf("critRate_" to 100.0),
            )

        fun defaultSubstats(profile: ArtifactOptimizationProfile): Set<String> =
            defaultPriorities(profile).toSet()

        fun defaultPriorities(profile: ArtifactOptimizationProfile): List<String> =
            profile.statWeights
                .filterValues { it >= 0.4 }
                .entries
                .sortedByDescending(Map.Entry<String, Double>::value)
                .map(Map.Entry<String, Double>::key)
    }
}

data class ArtifactBuildStats(
    val rows: List<ArtifactBuildStat>,
    val totalValues: Map<String, Double>,
    val critRateOverflow: Double,
    val critBalance: Double,
    val allMinimumTargetsMet: Boolean,
    val minimumCompletion: Double,
    val minimumCompletionSum: Double,
    val allMaximumTargetsMet: Boolean,
    val maximumViolation: Double,
)

data class ArtifactBuildStat(
    val key: String,
    val messageKey: String,
    val characterValue: Double,
    val weaponValue: Double,
    val bonusValue: Double,
    val artifactValue: Double,
    val totalValue: Double,
    val minimumTarget: Double?,
    val minimumMet: Boolean,
    val maximumTarget: Double?,
    val maximumMet: Boolean,
    val priority: Int?,
    val percentage: Boolean,
)

data class ArtifactStatOption(
    val key: String,
    val messageKey: String,
)

enum class ArtifactSetSelectionMode(
    val key: String,
) {
    CURRENT("current"),
    NONE("none"),
    CUSTOM("custom"),
    ;

    companion object {
        fun fromKey(key: String?): ArtifactSetSelectionMode =
            entries.find { it.key == key } ?: CURRENT
    }
}

data class ArtifactSetSelection(
    val mode: ArtifactSetSelectionMode,
    val requirements: List<ArtifactSetTarget> = emptyList(),
) {
    companion object {
        fun current(): ArtifactSetSelection =
            ArtifactSetSelection(ArtifactSetSelectionMode.CURRENT)
    }
}

data class ArtifactSetTarget(
    val setKey: String,
    val count: Int,
)

data class ScoredArtifact(
    val inventoryIndex: Int,
    val artifact: PlayerArtifact,
    val setName: String,
    val score: Double,
    val grade: String,
    val weightedRolls: Double,
    val mainStatFit: Double,
    val mainStatFitKey: String,
    val mainStatMessageKey: String,
    val mainStatFormattedValue: String,
    val stats: List<ScoredArtifactStat>,
    val rollQuality: Double,
    val usefulRollQuality: Double,
    val usefulRolls: Int,
    val totalRolls: Int,
    val usefulUpgradeRolls: Int,
    val upgradeRolls: Int,
    val targetEfficiency: Double,
    val currentArtifact: Boolean,
)

data class ScoredArtifactStat(
    val key: String,
    val messageKey: String,
    val formattedValue: String,
    val useful: Boolean,
    val rollCount: Int,
    val tierSummary: String,
    val rollQuality: Double,
)

data class ArtifactSetPlan(
    val requirements: List<ArtifactSetRequirement>,
)

data class ArtifactSetRequirement(
    val setKey: String,
    val setName: String,
    val count: Int,
)

data class ArtifactFarmingOutlook(
    val setKey: String,
    val setName: String,
    val comparedSlots: Int,
    val chancePerOnSetDrop: Double,
    val chancePerDomainFiveStar: Double,
    val chanceAfterTwentyDomainDrops: Double,
    val expectedDomainDrops: Double?,
)

data class ArtifactUpgradeOutlook(
    val piece: ScoredArtifact,
    val chancePerOnSetDrop: Double,
    val chancePerDomainFiveStar: Double,
    val expectedDomainDrops: Double?,
)

data class ArtifactLevelingCandidate(
    val piece: ScoredArtifact,
    val targetScore: Double,
    val optimisticScore: Double,
    val averageFinalScore: Double,
    val chanceToImprove: Double,
    val remainingUpgrades: Int,
    val likelyLowScore: Double,
    val likelyHighScore: Double,
    val expectedUsefulUpgrades: Double,
    val chanceAllUpgradesUseful: Double,
    val chanceNextUpgradeUseful: Double,
)

data class ArtifactLevelingRecommendation(
    val currentPiece: ScoredArtifact,
    val candidates: List<ArtifactLevelingCandidate>,
)

data class ArtifactLoadoutSuggestion(
    val pieces: List<ScoredArtifact>,
    val currentAverageScore: Double,
    val optimizedAverageScore: Double,
    val scoreGain: Double,
    val buildStats: ArtifactBuildStats,
    val transferredPieces: Int,
)
