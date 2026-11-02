package de.tyro.genshinapp.service.damage.v2.comparison

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.*
import de.tyro.genshinapp.service.artifact.ArtifactOptimizationService
import de.tyro.genshinapp.service.catalog.ArtifactCatalogService
import de.tyro.genshinapp.service.damage.v2.*
import org.mockito.Mockito.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import kotlin.test.*

class ArtifactComparisonServiceTest {
    private val mapper = jacksonObjectMapper()
    private val calculator = DamageFormulaServiceV2(mapper)
    private val teams = DamageFormulaTeamServiceV2(mapper)
    private val effects = DamageFormulaArtifactServiceV2(mapper)
    private val weapons = DamageFormulaWeaponServiceV2(mapper, effects)
    private val stats = ArtifactOptimizationService(ArtifactCatalogService(mapper))
    private val team = teams.compose(assertNotNull(calculator.profile("nefer")))
    private val goals = setOf("shades-hit-1", "shades-hit-2", "shades-hit-3")
    private val context = mock(ArtifactComparisonContextService::class.java)
    private val builds = mock(DamageFormulaBuildServiceV2::class.java)
    private val service = ArtifactComparisonService(context, calculator, teams, builds, effects, weapons, stats)

    @AfterTest fun close() { service.close() }

    private fun piece(slot: String, level: Int = 20, location: String? = "Nefer", em: Double = 40.0, set: String = "GildedDreams") =
        PlayerArtifact(set, slot, level, 5, when (slot) { "flower" -> "hp"; "plume" -> "atk"; else -> "eleMas" }, location, true,
            listOf(PlayerArtifactStat(if (slot in listOf("flower", "plume")) "eleMas" else "atk_", em),
                PlayerArtifactStat("critRate_", 7.0), PlayerArtifactStat("critDMG_", 14.0), PlayerArtifactStat("enerRech_", 5.0)),
            null, false, false)

    private fun setup(extra: List<PlayerArtifact>, unfinishedReference: Boolean = false): Pair<PlayerSnapshot, DamageFormulaProfileV2> {
        val gear = ArtifactComparisonService.SLOTS.map { piece(it, if (unfinishedReference && it == "flower") 16 else 20) } + extra
        val snapshot = PlayerSnapshot(1, "test", Instant.EPOCH, characters = team.selectedTeam.map { PlayerCharacterState(it, 90, 0, 6, 10, 10, 10) },
            inventory = emptyMap(), inventoryNames = emptyMap(), exportedInventoryKeys = 0, artifacts = gear,
            weapons = listOf(PlayerWeapon("ReliquaryOfTruth", 90, 6, 1, "Nefer", true)))
        val attached = weapons.attach(effects.attach(team, gear), snapshot.weapons)
        val profile = attached.copy(inputs = attached.inputs.map { input -> input.copy(currentValueLoaded = true,
            defaultValue = when (input.initialValueSource?.valueKey) {
                "totalAttack" -> 1500.0; "totalHp" -> 20000.0; "totalDefense" -> 1000.0
                "baseAttack" -> 800.0; "baseHp" -> 14000.0; "baseDefense" -> 800.0
                "stat:eleMas" -> 1000.0; "stat:critRate_" -> 65.0; "stat:critDMG_" -> 140.0
                else -> input.defaultValue
            }) })
        `when`(context.snapshot(1)).thenReturn(snapshot)
        `when`(context.fingerprint(profile, snapshot)).thenReturn("test-context")
        `when`(builds.applySnapshot(team, 1, snapshot)).thenReturn(profile)
        `when`(builds.comparisonBaseValues(snapshot, "nefer")).thenReturn(mapOf("baseHp" to 14000.0, "baseDefense" to 800.0))
        return snapshot to profile
    }

    private fun request(profile: DamageFormulaProfileV2) = ArtifactComparisonRequest(
        DamageCalculationRequestV2(profile.inputs.associate { it.id to it.defaultValue } + mapOf("elementalMastery" to 1150.0),
            profile.effects.associate { it.id to it.defaultEnabled }, team.selectedTeam), goals, "test-context")

    private fun await(id: String): ArtifactComparisonStatus {
        val deadline = System.nanoTime() + 60_000_000_000L
        while (System.nanoTime() < deadline) {
            val status = service.status("nefer", 1, id, null, "current", 0, 100)
            if (status.progress.phase in ComparisonTiming.TERMINAL) return status
            Thread.sleep(10)
        }
        fail("Comparison did not finish")
    }

    @Test fun `baseline and single replacements match V2 preserving offsets and team ownership`() {
        val (snapshot, profile) = setup(listOf(piece("flower", location = "Other", em = 100.0), piece("flower", location = "Nahida"),
            piece("flower", location = null, set = "UnknownSet")))
        val req = request(profile)
        val result = await(service.start("nefer", 1, req).runId)
        assertEquals("completed", result.progress.phase, result.error)
        val baseline = calculator.calculate(profile, req.calculation).results.filter { it.formulaId in goals }.sumOf { it.average }
        assertEquals(baseline, result.baselineDamage)
        assertEquals(0.0, result.rows.first { it.equipped }.current!!.difference, 1e-7)
        assertTrue(result.rows.none { it.artifact.location == "Nahida" })
        val candidate = result.rows.single { it.inventoryIndex == 5 }
        val expected = calculator.calculate(profile, req.calculation.copy(inputs = req.calculation.inputs + ("elementalMastery" to 1210.0)))
            .results.filter { it.formulaId in goals }.sumOf { it.average }
        assertEquals(expected, candidate.current!!.damage, 1e-7)
        assertTrue(candidate.current!!.difference > 0)
        assertEquals("Other", snapshot.artifacts[5].location)
        verify(context, times(1)).snapshot(1)
        verify(builds, times(1)).applySnapshot(team, 1, snapshot)
        assertFailsWith<ResponseStatusException> { service.status("nefer", 2, result.runId, null, "current", 0, 50) }
        assertFailsWith<ResponseStatusException> { service.cancel("nahida", 1, result.runId) }
    }

    @Test fun `leveling includes reference distribution and is reproducible`() {
        val (_, profile) = setup(listOf(piece("flower", 16, null, 70.0)), unfinishedReference = true)
        val first = await(service.start("nefer", 1, request(profile)).runId)
        assertEquals("completed", first.progress.phase, first.error)
        assertEquals(1606, first.progress.completedEvaluations)
        val projection = assertNotNull(first.rows.single { it.inventoryIndex == 5 }.projection)
        assertEquals(800, projection.samples)
        assertNotNull(projection.versusLeveled)
        assertNotNull(projection.chanceToBeatLeveled)
        assertTrue(projection.low <= projection.high)
        val second = await(service.start("nefer", 1, request(profile)).runId)
        assertEquals(projection, second.rows.single { it.inventoryIndex == 5 }.projection)
    }

    @Test fun `stale contexts and unknown goals are rejected before scheduling`() {
        val (_, profile) = setup(emptyList())
        assertFailsWith<ResponseStatusException> { service.start("nefer", 1, request(profile).copy(contextFingerprint = "old")) }
        assertFailsWith<IllegalArgumentException> { service.start("nefer", 1, request(profile).copy(formulaIds = setOf("unknown"))) }
    }

    @Test fun `losing a set bonus is recomposed and prepared V2 agrees with normal evaluation`() {
        val (snapshot, originalProfile) = setup(listOf(piece("flower", location = null, set = "WanderersTroupe")))
        // Four equipped Gilded pieces and an off-set circlet: the candidate breaks the four-piece effect.
        val originalGear = snapshot.artifacts.mapIndexed { index, artifact -> if (index == 4) artifact.copy(setKey = "DeepwoodMemories") else artifact }
        val modifiedSnapshot = snapshot.copy(artifacts = originalGear)
        val baselineLayout = weapons.attach(effects.attach(team, originalGear), snapshot.weapons)
        val profile = baselineLayout.copy(inputs = baselineLayout.inputs.map { input ->
            originalProfile.inputs.firstOrNull { it.id == input.id } ?: input
        })
        `when`(context.snapshot(1)).thenReturn(modifiedSnapshot)
        `when`(context.fingerprint(profile, modifiedSnapshot)).thenReturn("test-context")
        `when`(builds.applySnapshot(team, 1, modifiedSnapshot)).thenReturn(profile)
        `when`(builds.comparisonBaseValues(modifiedSnapshot, "nefer")).thenReturn(mapOf("baseHp" to 14000.0, "baseDefense" to 800.0))
        val req = request(profile)
        val result = await(service.start("nefer", 1, req).runId)
        assertEquals("completed", result.progress.phase)
        val replaced = originalGear.take(5).mapIndexed { index, artifact -> if (index == 0) originalGear[5].copy(location = "Nefer") else artifact }
        val newLayout = weapons.attach(effects.attach(team, replaced), snapshot.weapons)
        val expected = calculator.calculate(newLayout, req.calculation)
        assertEquals(expected, calculator.prepare(newLayout)(req.calculation))
        assertEquals(expected.results.filter { it.formulaId in goals }, calculator.prepare(newLayout, goals)(req.calculation).results)
        val expectedDamage = expected.results.filter { it.formulaId in goals }.sumOf { it.average }
        val candidate = result.rows.single { it.inventoryIndex == 5 }
        assertEquals(expectedDamage, candidate.current!!.damage, 1e-7)
        assertTrue(candidate.current!!.difference < 0)
    }

    @Test fun `HP percent and flat stats use the base while manual totals remain offsets`() {
        val (_, profile) = setup(emptyList())
        val old = piece("flower")
        val next = old.copy(substats = old.substats + PlayerArtifactStat("hp_", 10.0))
        val entered = profile.inputs.associate { it.id to it.defaultValue } + ("maxHp" to 30000.0)
        val adjusted = service.adjustedInputs(profile, profile, entered, old, next, mapOf("baseHp" to 14000.0, "baseDefense" to 800.0))
        assertEquals(31400.0, adjusted["maxHp"])
        assertEquals(entered["elementalMastery"], adjusted["elementalMastery"])
    }

    @Test fun `INFO logs correlate runs without per sample noise and report terminal counts`() {
        val log = org.slf4j.LoggerFactory.getLogger(ArtifactComparisonService::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start(); log.addAppender(appender)
        try {
            val (_, profile) = setup(listOf(piece("flower", 16, null)))
            val result = await(service.start("nefer", 1, request(profile)).runId)
            // The terminal state becomes visible just before its final log is emitted.
            val deadline = System.nanoTime() + 2_000_000_000
            while (appender.list.none { it.formattedMessage.contains("terminal=") } && System.nanoTime() < deadline) Thread.sleep(5)
            val messages = appender.list.filter { it.level.isGreaterOrEqual(ch.qos.logback.classic.Level.INFO) }.map { it.formattedMessage }
            assertTrue(messages.all { "run=${result.runId}" in it })
            assertTrue(messages.any { "terminal=completed" in it && "evaluations=" in it && "phaseMs=" in it })
            assertTrue(messages.size < 15, "Must not log all 800 samples")
        } finally { log.detachAppender(appender); appender.stop() }
    }

    @Test fun `large inventory cancellation retains partial progress and disallows overlapping runs`() {
        val (_, profile) = setup((1..200).map { piece("flower", 0, null, it.toDouble()) })
        val run = service.start("nefer", 1, request(profile))
        assertFailsWith<ResponseStatusException> { service.start("nefer", 1, request(profile)) }
        service.cancel("nefer", 1, run.runId)
        val result = await(run.runId)
        assertEquals("cancelled", result.progress.phase)
        assertTrue(result.progress.percent < 100)
    }

    @Test fun `large inventory completes with no per candidate build reads`() {
        val (snapshot, profile) = setup((1..1000).map { piece("flower", location = null, em = (it % 100).toDouble()) } +
            (1..3).map { piece("flower", 16, null, 30.0 + it) })
        val result = await(service.start("nefer", 1, request(profile)).runId)
        assertEquals("completed", result.progress.phase, result.error)
        assertEquals(1008, result.totalRows)
        assertEquals(3408L, result.progress.completedEvaluations)
        assertEquals(100.0, result.progress.percent)
        verify(context, times(1)).snapshot(1)
        verify(builds, times(1)).applySnapshot(team, 1, snapshot)
        verify(builds, times(1)).comparisonBaseValues(snapshot, "nefer")
        val sorted = service.status("nefer", 1, result.runId, "flower", "potential", 0, 100)
        assertEquals(100, sorted.rows.size)
        assertEquals("flower", sorted.rows.first().artifact.slotKey)
    }
}
