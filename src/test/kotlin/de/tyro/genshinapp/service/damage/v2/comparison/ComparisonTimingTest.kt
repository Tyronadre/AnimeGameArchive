package de.tyro.genshinapp.service.damage.v2.comparison

import kotlin.test.*

class ComparisonTimingTest {
    @Test fun `warmup and independent phase rates avoid misleading projection estimates`() {
        var nanos = 0L
        val timing = ComparisonTiming { nanos }
        timing.totals(10, 800)
        nanos = 2_000_000_000
        timing.transition("preparing")
        assertEquals(2000, timing.snapshot(0, 1).queueMillis)
        timing.transition("current")
        repeat(2) { nanos += 800_000_000; timing.batch(1) }
        assertNull(timing.snapshot(0, 1).remainingMillis)
        nanos += 800_000_000; timing.batch(1)
        assertEquals(5600, timing.snapshot(0, 1).remainingMillis)
        assertEquals("phase", timing.snapshot(0, 1).estimateScope)
        timing.transition("projections")
        assertNull(timing.snapshot(0, 1).remainingMillis)
        repeat(3) { nanos += 1_000_000_000; timing.batch(32) }
        val measured = timing.snapshot(0, 1)
        assertEquals("run", measured.estimateScope)
        assertEquals(22000, measured.remainingMillis)
        nanos += 4_000_000_000; timing.batch(32)
        assertTrue(timing.snapshot(0, 1).remainingMillis!! > measured.remainingMillis!!)
        timing.transition("cancelled")
        val cancelled = timing.snapshot(0, 1)
        nanos += 5_000_000_000
        assertEquals(cancelled.elapsedMillis, timing.snapshot(0, 1).elapsedMillis)
        assertNull(cancelled.remainingMillis)
        assertTrue(cancelled.percent < 100)
    }

    @Test fun `empty completed run and failures have correct terminal timing`() {
        val timing = ComparisonTiming { 0 }
        timing.transition("preparing"); timing.totals(0, 0); timing.transition("completed")
        assertEquals(100.0, timing.snapshot(0, 0).percent)
        assertEquals(0L, timing.snapshot(0, 0).remainingMillis)
        val failed = ComparisonTiming { 0 }
        failed.transition("failed")
        assertNull(failed.snapshot(0, 5).remainingMillis)
        assertEquals(0.0, failed.snapshot(0, 5).percent)
    }

    @Test fun `projection compares independent distributions and handles zero baseline`() {
        val result = DamageProjectionMath.summarize(doubleArrayOf(10.0, 20.0, 30.0), 0.0, doubleArrayOf(15.0, 25.0))
        assertEquals(20.0, result.expected.damage)
        assertNull(result.expected.percent)
        assertEquals(.5, result.chanceToBeatLeveled)
        assertEquals(1.0, result.chanceToImprove)
        assertEquals(0.0, result.versusLeveled!!.difference)
        assertEquals(0.0, DamageProjectionMath.summarize(doubleArrayOf(10.0), 10.0, doubleArrayOf(10.0)).chanceToBeatLeveled)
    }
}
