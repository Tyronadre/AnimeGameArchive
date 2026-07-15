package de.tyro.genshinapp.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CharacterProgressFormTest {
    @Test
    fun `current progress infers ownership when ownership was not explicitly changed`() {
        val progress = CharacterProgressForm().also {
            it.level = 40
            it.ascension = 2
            it.normalTalent = 4
        }.normalized()

        assertTrue(progress.owned)
        assertEquals(40, progress.level)
        assertEquals(4, progress.normalTalent)
    }

    @Test
    fun `explicitly marking a character unowned still resets current progress`() {
        val progress = CharacterProgressForm().also {
            it.ownershipExplicit = true
            it.level = 40
            it.ascension = 2
            it.normalTalent = 4
        }.normalized()

        assertFalse(progress.owned)
        assertEquals(1, progress.level)
        assertEquals(1, progress.normalTalent)
    }

    @Test
    fun `target-only edits do not infer ownership`() {
        val progress = CharacterProgressForm().also {
            it.targetLevel = 90
            it.targetNormalTalent = 10
        }.normalized()

        assertFalse(progress.owned)
        assertEquals(90, progress.targetLevel)
        assertEquals(10, progress.targetNormalTalent)
    }
}
