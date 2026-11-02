package de.tyro.genshinapp.service.damage.v2.comparison

import de.tyro.genshinapp.model.*
import de.tyro.genshinapp.service.artifact.ArtifactOptimizationService
import de.tyro.genshinapp.service.artifact.ArtifactUpgradeSimulator
import de.tyro.genshinapp.service.damage.v2.*
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

@Service
class ArtifactComparisonService(
    private val contextService: ArtifactComparisonContextService,
    private val calculator: DamageFormulaServiceV2,
    private val teams: DamageFormulaTeamServiceV2,
    private val builds: DamageFormulaBuildServiceV2,
    private val artifactEffects: DamageFormulaArtifactServiceV2,
    private val weaponEffects: DamageFormulaWeaponServiceV2,
    private val artifactStats: ArtifactOptimizationService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        ThreadFactory { task -> Thread(task, "artifact-comparison").apply { isDaemon = true } })
    private val cleanup = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "artifact-comparison-cleanup").apply { isDaemon = true }
    }
    private val runs = ConcurrentHashMap<String, Run>()
    init { cleanup.scheduleAtFixedRate({ expire() }, 1, 1, TimeUnit.MINUTES) }

    private class Run(
        val id: String, val userId: Long, val key: String, val fingerprint: String,
        val request: ArtifactComparisonRequest,
        val snapshot: PlayerSnapshot,
        val team: DamageFormulaProfileV2, val profile: DamageFormulaProfileV2,
        val artifacts: Map<Int, PlayerArtifact>, val equipped: Map<String, Int>,
        val baseValues: Map<String, Double>,
    ) {
        val teamArtifacts = snapshot.artifacts.withIndex().filter { owner(it.value.location) in profile.selectedTeam }
        val timing = ComparisonTiming()
        val cancelled = AtomicBoolean()
        val rows = ConcurrentHashMap<Int, ArtifactComparisonRow>()
        @Volatile var phase = "queued"
        @Volatile var baseline: Double? = null
        @Volatile var error: String? = null
        @Volatile var completed = 0
        @Volatile var finishedAt: Long? = null
        var task: Runnable? = null
        val formulaRevision = DamageFormulaResourcesV2.revision.get()
        var candidate: Int? = null
        var sample: Int? = null
        var seed: Int? = null
        var lastLog = System.nanoTime()
    }

    @Synchronized fun start(key: String, userId: Long, request: ArtifactComparisonRequest): ArtifactComparisonStatus {
        expire()
        // Keep retained result memory bounded even for repeated quick analyses.
        runs.values.filter { it.userId == userId && it.finishedAt != null }.sortedByDescending { it.finishedAt }
            .drop(2).forEach { runs.remove(it.id) }
        runs.values.filter { it.finishedAt != null }.sortedByDescending { it.finishedAt }
            .drop(48).forEach { runs.remove(it.id) }
        if (runs.values.any { it.userId == userId && it.phase !in ComparisonTiming.TERMINAL }) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "comparison.alreadyRunning")
        }
        val id = UUID.randomUUID().toString()
        try {
            require(request.calculation.inputs.values.all(Double::isFinite)) { "comparison.invalidInputs" }
            require(request.calculation.teamKeys.isNotEmpty()) { "comparison.explicitTeam" }
            val snapshot = contextService.snapshot(userId) ?: invalid("comparison.noInventory")
            val base = calculator.profile(key) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "comparison.noProfile")
            val team = teams.compose(base, request.calculation.teamKeys)
            val profile = builds.applySnapshot(team, userId, snapshot)
            val fingerprint = contextService.fingerprint(profile, snapshot)
            if (fingerprint != request.contextFingerprint) throw ResponseStatusException(HttpStatus.CONFLICT, "comparison.staleContext")
            val goals = team.talents.flatMap { it.formulas }.map { it.id }.toSet()
            require(request.formulaIds.isNotEmpty() && goals.containsAll(request.formulaIds)) { "comparison.invalidGoal" }
            val selected = profile.selectedTeam
            require(selected.all { member -> snapshot.characters.any { owner(it.key) == member } }) { "comparison.missingBuild" }
            val equippedArtifacts = snapshot.artifacts.withIndex().filter { owner(it.value.location) == profile.characterKey }
            require(equippedArtifacts.map { it.value.slotKey.lowercase() }.toSet() == SLOTS.toSet() && equippedArtifacts.size == 5) { "comparison.incompleteLoadout" }
            val supportedWeapons = weaponEffects.allDefinitions().map { it.key }.toSet()
            require(snapshot.weapons.filter { owner(it.location) in selected }.all { GoodKeyNormalizer.normalize(it.key) in supportedWeapons }) { "comparison.unsupportedTeamWeapon" }
            require(profile.artifactLoadouts.all { loadout -> loadout.sets.none { !it.supported && it.pieces >= 2 } }) { "comparison.unsupportedTeamSet" }
            require(profile.inputs.filter { it.initialValueSource != null }.all { it.currentValueLoaded }) { "comparison.missingBuild" }
            require(snapshot.artifacts.filter { owner(it.location) in selected }.all { artifactIssue(it) == null }) { "comparison.invalidTeamArtifact" }
            val equipped = equippedArtifacts.associate { it.value.slotKey.lowercase() to it.index }
            val candidates = snapshot.artifacts.withIndex().filter { indexed ->
                indexed.value.slotKey.lowercase() in SLOTS &&
                    (owner(indexed.value.location) !in selected || owner(indexed.value.location) == profile.characterKey)
            }.associate { it.index to it.value }
            val baseValues = builds.comparisonBaseValues(snapshot, profile.characterKey)
            require(baseValues.keys.containsAll(listOf("baseHp", "baseDefense"))) { "comparison.missingBuild" }
            val run = Run(id, userId, key, fingerprint, request, snapshot, team, profile, candidates, equipped, baseValues)
            candidates.forEach { (index, artifact) ->
                run.rows[index] = ArtifactComparisonRow(index, artifact, index in equipped.values,
                    exclusion = artifactIssue(artifact), projectionUnavailable = if (artifact.level < 20) ArtifactUpgradeSimulator.unavailableReason(artifact) else null)
            }
            runs[id] = run
            val task = Runnable { execute(run) }
            run.task = task
            try { executor.execute(task) } catch (error: RejectedExecutionException) {
                runs.remove(id)
                throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "comparison.busy")
            }
            logger.info("Artifact comparison run={} accepted user={} character={} candidates={}", id, userId, key, candidates.size)
            logger.debug("Artifact comparison run={} team={} goals={} inputs={} effects={} simulationVersion={}",
                id, selected, request.formulaIds, request.calculation.inputs, request.calculation.enabledEffects, ArtifactUpgradeSimulator.VERSION)
            logger.debug("Artifact comparison run={} manualOffsets={}", id, profile.inputs.mapNotNull { input ->
                request.calculation.inputs[input.id]?.minus(input.defaultValue)?.takeIf { it != 0.0 }?.let { input.id to it }
            }.toMap())
            return status(run, null, "current", 0, 50)
        } catch (error: IllegalArgumentException) {
            logger.warn("Artifact comparison run={} rejected reason={}", id, error.message)
            throw error
        } catch (error: ResponseStatusException) {
            logger.warn("Artifact comparison run={} rejected status={} reason={}", id, error.statusCode, error.reason)
            throw error
        }
    }

    fun status(key: String, userId: Long, id: String, slot: String?, sort: String, offset: Int, limit: Int): ArtifactComparisonStatus =
        status(owned(key, userId, id), slot, sort, offset, limit)

    fun cancel(key: String, userId: Long, id: String): ArtifactComparisonStatus {
        val run = owned(key, userId, id)
        synchronized(run) {
            if (run.phase !in ComparisonTiming.TERMINAL && run.cancelled.compareAndSet(false, true)) {
                logger.info("Artifact comparison run={} cancellation requested phase={}", id, run.phase)
                if (run.phase == "queued") {
                    run.task?.let(executor::remove)
                    phase(run, "cancelled")
                    run.finishedAt = System.nanoTime()
                    logger.info("Artifact comparison run={} terminal=cancelled before execution queueMs={}", id, run.timing.snapshot(0, run.artifacts.size).queueMillis)
                }
            }
        }
        return status(run, null, "current", 0, 50)
    }

    private fun owned(key: String, userId: Long, id: String): Run = runs[id]?.takeIf { it.userId == userId && it.key == key }
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "comparison.runNotFound")

    private fun status(run: Run, slot: String?, sort: String, offset: Int, limit: Int): ArtifactComparisonStatus {
        require(offset >= 0 && limit in 1..100 && (slot == null || slot in SLOTS)) { "comparison.invalidPage" }
        require(sort in setOf("current", "potential", "chance")) { "comparison.invalidSort" }
        val rows = run.rows.values.filter { slot == null || it.artifact.slotKey.lowercase() == slot }.sortedWith(
            compareBy<ArtifactComparisonRow> { SLOTS.indexOf(it.artifact.slotKey.lowercase()) }
                .thenByDescending { it.equipped }.thenByDescending { row -> when (sort) {
                    "potential" -> row.projection?.expected?.damage ?: if (row.artifact.level == 20) row.current?.damage else null
                    "chance" -> row.projection?.chanceToImprove
                    else -> row.current?.damage
                } ?: Double.NEGATIVE_INFINITY }.thenBy { it.inventoryIndex })
        return ArtifactComparisonStatus(run.id, run.fingerprint, run.timing.snapshot(run.completed, run.artifacts.size),
            run.baseline, rows.drop(offset).take(limit), rows.size, offset, run.error, run.cancelled.get())
    }

    private fun execute(run: Run) {
        synchronized(run) {
            if (run.phase == "cancelled") return
            phase(run, "preparing")
        }
        try {
            checkCancelled(run)
            val referenceCalculation = calculator.prepare(run.profile, run.request.formulaIds)(run.request.calculation)
            val baseline = goal(referenceCalculation, run.request.formulaIds)
            run.baseline = baseline
            val baselineInputs = referenceCalculation.inputs
            val supportedSets = artifactEffects.allDefinitions().map { it.key }.toSet()
            val evaluators = mutableMapOf<String, Pair<DamageFormulaProfileV2, (DamageCalculationRequestV2) -> DamageCalculationV2>>()
            val layouts = mutableMapOf<Int, String>()
            run.artifacts.forEach { (index, artifact) ->
                checkCancelled(run)
                if (run.rows.getValue(index).exclusion != null) return@forEach
                val replacement = replace(run, index, artifact)
                val counts = replacement.filter { owner(it.location) == run.profile.characterKey }.groupingBy { GoodKeyNormalizer.normalize(it.setKey) }.eachCount()
                if (counts.any { (set, count) -> count >= 2 && set !in supportedSets }) {
                    run.rows.computeIfPresent(index) { _, row -> row.copy(exclusion = "unsupportedSet") }
                    return@forEach
                }
                val layout = counts.toSortedMap().toString()
                layouts[index] = layout
                if (layout !in evaluators) {
                    val attached = weaponEffects.attach(artifactEffects.attach(run.team, replacement), run.snapshot.weapons)
                    val originalInputs = run.profile.inputs.associateBy { it.id }
                    val profile = attached.copy(inputs = attached.inputs.map { input ->
                        originalInputs[input.id]?.let { input.copy(defaultValue = it.defaultValue, currentValueLoaded = it.currentValueLoaded) } ?: input
                    })
                    evaluators[layout] = profile to calculator.prepare(profile, run.request.formulaIds)
                }
            }
            val eligible = layouts.keys.toList()
            val projectable = eligible.filter { index -> run.artifacts.getValue(index).let {
                it.level < 20 && ArtifactUpgradeSimulator.unavailableReason(it) == null
            } }
            run.timing.totals(eligible.size.toLong(), projectable.size.toLong() * SAMPLES)
            logger.debug("Artifact comparison run={} preparedLayouts={} eligible={} projections={}", run.id, evaluators.size, eligible.size, projectable.size)
            val exclusions = run.rows.values.mapNotNull { it.exclusion }.groupingBy { it }.eachCount()
            if (exclusions.isNotEmpty()) logger.warn("Artifact comparison run={} excluded={}", run.id, exclusions)

            fun evaluate(index: Int, artifact: PlayerArtifact): Double {
                val (profile, evaluator) = evaluators.getValue(layouts.getValue(index))
                val original = run.artifacts.getValue(run.equipped.getValue(artifact.slotKey.lowercase()))
                val inputs = adjustedInputs(run.profile, profile, baselineInputs, original, artifact, run.baseValues)
                return goal(evaluator(run.request.calculation.copy(inputs = inputs)), run.request.formulaIds)
            }

            phase(run, "current")
            run.completed = run.artifacts.size - eligible.size
            eligible.forEach { index ->
                checkCancelled(run); run.candidate = index
                val before = System.nanoTime()
                val damage = evaluate(index, run.artifacts.getValue(index))
                val profile = evaluators.getValue(layouts.getValue(index)).first
                run.rows.computeIfPresent(index) { _, row -> row.copy(current = DamageGain.against(damage, baseline),
                    newEffectDefaults = profile.effects.filter { effect -> run.profile.effects.none { it.id == effect.id } }.associate { it.name to it.defaultEnabled },
                    newInputDefaults = profile.inputs.filter { input -> run.profile.inputs.none { it.id == input.id } && input.initialValueSource == null }.associate { it.label to it.defaultValue }) }
                run.completed++; run.timing.batch(1); progressLog(run)
                logger.debug("Artifact comparison run={} candidate={} currentDamage={} durationMs={}", run.id, index, damage, (System.nanoTime() - before) / 1_000_000)
            }

            phase(run, "projections")
            run.completed = run.artifacts.size - projectable.size
            val referenceDistributions = mutableMapOf<String, DoubleArray>()
            run.equipped.forEach { (slot, index) ->
                if (index !in projectable && run.artifacts.getValue(index).level == 20) referenceDistributions[slot] = doubleArrayOf(baseline)
            }
            val ordered = projectable.sortedBy { it !in run.equipped.values }
            ordered.forEach { index ->
                checkCancelled(run); run.candidate = index
                val artifact = run.artifacts.getValue(index)
                val seed = ArtifactUpgradeSimulator.seed(artifact)
                run.seed = seed
                val random = Random(seed)
                val before = System.nanoTime()
                val samples = DoubleArray(SAMPLES)
                for (batchStart in 0 until SAMPLES step BATCH) {
                    checkCancelled(run)
                    val end = minOf(batchStart + BATCH, SAMPLES)
                    for (i in batchStart until end) {
                        run.sample = i
                        samples[i] = evaluate(index, ArtifactUpgradeSimulator.finish(artifact.copy(substats = artifact.substats.sortedBy { it.key }), random))
                    }
                    run.timing.batch(end - batchStart); progressLog(run)
                }
                val equipped = index in run.equipped.values
                val slot = artifact.slotKey.lowercase()
                val projection = DamageProjectionMath.summarize(samples, baseline, if (equipped) null else referenceDistributions[slot])
                if (equipped) referenceDistributions[slot] = samples
                run.rows.computeIfPresent(index) { _, row -> row.copy(projection = projection) }
                run.completed++
                logger.debug("Artifact comparison run={} candidate={} seed={} samples={} mean={} low={} high={} chance={} durationMs={}",
                    run.id, index, seed, SAMPLES, projection.expected.damage, projection.low, projection.high, projection.chanceToImprove, (System.nanoTime() - before) / 1_000_000)
            }
            checkCancelled(run)
            phase(run, "completed")
        } catch (_: CancellationException) {
            phase(run, "cancelled")
        } catch (error: Exception) {
            logger.error("Artifact comparison run=${run.id} phase=${run.phase} candidate=${run.candidate} seed=${run.seed} sample=${run.sample} failed", error)
            run.error = if (run.formulaRevision != DamageFormulaResourcesV2.revision.get()) "comparison.staleContext" else "comparison.failed"
            phase(run, "failed")
        } finally {
            run.finishedAt = System.nanoTime()
            val progress = run.timing.snapshot(run.completed, run.artifacts.size)
            logger.info("Artifact comparison run={} terminal={} durationMs={} phaseMs={} candidates={}/{} evaluations={}/{} excluded={} failures={} evaluationsPerSecond={}",
                run.id, run.phase, progress.elapsedMillis, run.timing.phaseDurations(), run.completed, run.artifacts.size,
                progress.completedEvaluations, progress.totalEvaluations, run.rows.values.count { it.exclusion != null },
                if (run.error == null) 0 else 1, progress.completedEvaluations * 1000.0 / progress.elapsedMillis.coerceAtLeast(1))
        }
    }

    internal fun adjustedInputs(
        baseline: DamageFormulaProfileV2, candidate: DamageFormulaProfileV2, entered: Map<String, Double>,
        old: PlayerArtifact, replacement: PlayerArtifact,
        baseValues: Map<String, Double> = emptyMap(),
    ): Map<String, Double> {
        val oldStats = stats(old); val nextStats = stats(replacement)
        fun delta(key: String) = nextStats.getOrDefault(key, 0.0) - oldStats.getOrDefault(key, 0.0)
        fun base(valueKey: String): Double = baseline.inputs.firstOrNull {
            it.initialValueSource?.let { source -> (source.characterKey ?: baseline.characterKey) == baseline.characterKey && source.valueKey == valueKey } == true
        }?.let { entered[it.id] ?: it.defaultValue } ?: baseValues[valueKey] ?: error("Missing $valueKey for artifact comparison")
        return candidate.inputs.associate { input ->
            val source = input.initialValueSource
            val change = if (source == null || (source.characterKey ?: baseline.characterKey) != baseline.characterKey) 0.0 else when {
                source.valueKey.startsWith("stat:") -> delta(source.valueKey.removePrefix("stat:"))
                source.valueKey == "totalAttack" -> base("baseAttack") * delta("atk_") / 100 + delta("atk")
                source.valueKey == "totalHp" -> base("baseHp") * delta("hp_") / 100 + delta("hp")
                source.valueKey == "totalDefense" -> base("baseDefense") * delta("def_") / 100 + delta("def")
                else -> 0.0
            }
            input.id to ((entered[input.id] ?: input.defaultValue) + change)
        }
    }

    private fun stats(artifact: PlayerArtifact): Map<String, Double> {
        val stats = artifact.substats.associate { it.key to it.value }.toMutableMap()
        stats[artifact.mainStatKey] = stats.getOrDefault(artifact.mainStatKey, 0.0) + artifactStats.artifactMainStatValue(artifact)
        return stats
    }

    private fun artifactIssue(artifact: PlayerArtifact): String? = when {
        artifact.rarity !in 3..5 || artifact.level !in 0..(artifact.rarity * 4) || artifactStats.artifactMainStatValue(artifact) <= 0 -> "unknownStats"
        artifact.substats.size > 4 || artifact.substats.map { it.key }.distinct().size != artifact.substats.size ||
            artifact.substats.any { it.key == artifact.mainStatKey || !it.value.isFinite() || it.value < 0 || it.key !in ArtifactUpgradeSimulator.rollValues } -> "invalidArtifact"
        else -> null
    }

    private fun replace(run: Run, index: Int, artifact: PlayerArtifact): List<PlayerArtifact> {
        val target = run.equipped.getValue(artifact.slotKey.lowercase())
        return run.teamArtifacts.mapNotNull { (i, existing) -> when {
            i == target -> artifact.copy(location = run.profile.characterKey)
            i == index -> null
            else -> existing
        } }
    }

    private fun goal(calculation: DamageCalculationV2, ids: Set<String>): Double =
        calculation.results.filter { it.formulaId in ids }.sumOf { it.average }.also { require(it.isFinite()) { "Non-finite damage" } }

    private fun checkCancelled(run: Run) {
        if (run.cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException()
        check(run.formulaRevision == DamageFormulaResourcesV2.revision.get()) { "Formula definitions changed during analysis" }
    }
    private fun phase(run: Run, phase: String) {
        run.timing.transition(phase); run.phase = phase
        logger.info("Artifact comparison run={} phase={}", run.id, phase)
    }
    private fun progressLog(run: Run) {
        if (System.nanoTime() - run.lastLog >= TimeUnit.SECONDS.toNanos(30)) {
            run.lastLog = System.nanoTime()
            logger.info("Artifact comparison run={} progress={}", run.id, run.timing.snapshot(run.completed, run.artifacts.size))
        }
    }
    private fun expire() {
        val now = System.nanoTime()
        runs.entries.removeIf { (_, run) -> run.finishedAt?.let { now - it > TimeUnit.MINUTES.toNanos(30) } == true }
    }
    @PreDestroy fun close() { runs.values.forEach { it.cancelled.set(true) }; executor.shutdownNow(); cleanup.shutdownNow() }
    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
    companion object {
        const val SAMPLES = 800
        private const val BATCH = 32
        val SLOTS = listOf("flower", "plume", "sands", "goblet", "circlet")
        fun owner(value: String?) = TravelerIdentity.canonicalCharacterKey(value.orEmpty())
    }
}
