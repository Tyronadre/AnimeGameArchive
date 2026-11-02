package de.tyro.genshinapp.service.damage.v2.comparison

import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.service.damage.v2.DamageCalculationRequestV2

data class ArtifactComparisonRequest(
    val calculation: DamageCalculationRequestV2,
    val formulaIds: Set<String>,
    val contextFingerprint: String,
)

data class DamageGain(val damage: Double, val difference: Double, val percent: Double?) {
    companion object {
        fun against(damage: Double, baseline: Double) = DamageGain(damage, damage - baseline,
            if (baseline > 0) (damage / baseline - 1) * 100 else null)
    }
}

data class DamageProjection(
    val samples: Int,
    val expected: DamageGain,
    val low: Double,
    val high: Double,
    val chanceToImprove: Double,
    val versusLeveled: DamageGain?,
    val chanceToBeatLeveled: Double?,
)

data class ArtifactComparisonRow(
    val inventoryIndex: Int,
    val artifact: PlayerArtifact,
    val equipped: Boolean,
    val current: DamageGain? = null,
    val projection: DamageProjection? = null,
    val exclusion: String? = null,
    val projectionUnavailable: String? = null,
    val newEffectDefaults: Map<String, Boolean> = emptyMap(),
    val newInputDefaults: Map<String, Double> = emptyMap(),
)

data class ComparisonProgress(
    val phase: String,
    val completedCandidates: Int,
    val totalCandidates: Int,
    val completedEvaluations: Long,
    val totalEvaluations: Long,
    val percent: Double,
    val elapsedMillis: Long,
    val queueMillis: Long,
    val remainingMillis: Long?,
    val estimateScope: String?,
)

data class ArtifactComparisonStatus(
    val runId: String,
    val contextFingerprint: String,
    val progress: ComparisonProgress,
    val baselineDamage: Double?,
    val rows: List<ArtifactComparisonRow>,
    val totalRows: Int,
    val offset: Int,
    val error: String?,
    val cancellationRequested: Boolean,
)

/** Monotonic timing, independent phase rates, and bounded smoothing. All access is synchronized. */
class ComparisonTiming(private val nanoTime: () -> Long = System::nanoTime) {
    private val created = nanoTime()
    private var started: Long? = null
    private var ended: Long? = null
    private var phaseStarted = created
    private var lastBatch = created
    private var phase = "queued"
    private var currentTotal = 0L
    private var projectionTotal = 0L
    private var currentDone = 0L
    private var projectionDone = 0L
    private val rates = mutableMapOf<String, Rate>()
    private val durations = linkedMapOf<String, Long>()
    private data class Rate(var batches: Int = 0, var elapsed: Long = 0, var nanosPerEvaluation: Double = 0.0)

    @Synchronized fun totals(current: Long, projections: Long) {
        currentTotal = current; projectionTotal = projections
    }

    @Synchronized fun transition(next: String) {
        val now = nanoTime()
        durations[phase] = (durations[phase] ?: 0) + (now - phaseStarted) / 1_000_000
        if (started == null && next != "queued") started = now
        if (next in TERMINAL) ended = now
        phase = next; phaseStarted = now; lastBatch = now
    }

    @Synchronized fun batch(evaluations: Int) {
        if (evaluations <= 0) return
        val now = nanoTime()
        val elapsed = (now - lastBatch).coerceAtLeast(1)
        lastBatch = now
        if (phase == "current") currentDone += evaluations else if (phase == "projections") projectionDone += evaluations
        val rate = rates.getOrPut(phase) { Rate() }
        val measurement = elapsed.toDouble() / evaluations
        rate.nanosPerEvaluation = if (rate.batches == 0) measurement else rate.nanosPerEvaluation * .75 + measurement * .25
        rate.batches++; rate.elapsed += elapsed
    }

    @Synchronized fun phaseDurations(): Map<String, Long> = durations.toMap()

    @Synchronized fun snapshot(completed: Int, total: Int): ComparisonProgress {
        val now = ended ?: nanoTime()
        val done = currentDone + projectionDone
        val all = currentTotal + projectionTotal
        val rate = rates[phase]?.takeIf { it.batches >= 3 && it.elapsed >= 2_000_000_000L }
        val remaining = when (phase) {
            "current" -> currentTotal - currentDone
            "projections" -> projectionTotal - projectionDone
            else -> 0
        }.coerceAtLeast(0)
        val eta = if (phase == "completed") 0L else rate?.let { (remaining * it.nanosPerEvaluation / 1_000_000).toLong() }
        val scope = if (eta == null) null else if (phase == "current" && projectionTotal > 0) "phase" else "run"
        return ComparisonProgress(phase, completed, total, done, all,
            if (phase == "completed") 100.0 else if (all == 0L) 0.0 else (100.0 * done / all).coerceAtMost(99.9),
            started?.let { (now - it) / 1_000_000 } ?: 0,
            ((started ?: now) - created) / 1_000_000, eta, scope)
    }

    companion object { val TERMINAL = setOf("completed", "cancelled", "failed") }
}

object DamageProjectionMath {
    fun summarize(values: DoubleArray, baseline: Double, reference: DoubleArray?): DamageProjection {
        require(values.isNotEmpty() && values.all(Double::isFinite))
        val sorted = values.sortedArray()
        val average = sorted.average()
        val target = reference?.sortedArray()
        fun beats(value: Double, other: Double) = value - other > maxOf(1e-8, kotlin.math.abs(other) * 1e-10)
        // Empirical independent distributions; binary search avoids a quadratic sample comparison.
        val chance = target?.let { targets -> sorted.sumOf { value ->
            var low = 0; var high = targets.size
            while (low < high) { val mid = (low + high) / 2; if (beats(value, targets[mid])) low = mid + 1 else high = mid }
            low.toDouble() / targets.size
        } / sorted.size }
        return DamageProjection(values.size, DamageGain.against(average, baseline),
            sorted[(sorted.lastIndex * .1).toInt()], sorted[(sorted.lastIndex * .9).toInt()],
            sorted.count { beats(it, baseline) }.toDouble() / sorted.size,
            target?.let { DamageGain.against(average, it.average()) }, chance)
    }
}
