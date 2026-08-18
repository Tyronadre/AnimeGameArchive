package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.PlayerWeapon
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals

class WeaponPlanningServiceTest {
    private val service = WeaponPlanningService(
        mock(WeaponDataService::class.java),
        mock(MaterialCatalogService::class.java),
        mock(PlayerPlanningService::class.java),
    )

    @Test
    fun `calculates exact enhancement ore and mora between weapon breakpoints`() {
        val weapon = PlayerWeapon(
            key = "Rust",
            level = 20,
            ascension = 6,
            refinement = 1,
            location = "Tartaglia",
            locked = true,
        )
        val definition = WeaponDefinition(
            key = "rust",
            name = "Rust",
            rarity = 4,
            ascensionCosts = emptyMap(),
        )

        val requirements = service.calculateRequirements(weapon, definition, 90)

        assertEquals(
            597L,
            requirements.single { it.id == 104013 }.amount,
        )
        assertEquals(
            597_000L,
            requirements.single { it.id == 202 }.amount,
        )
    }

    @Test
    fun `calculates from the exact imported level instead of the previous ascension cap`() {
        val enhancement = service.calculateEnhancement(
            rarity = 4,
            currentLevel = 37,
            targetLevel = 40,
        )

        assertEquals(92_200L, enhancement.experience)
        assertEquals(10L, enhancement.mysticEnhancementOre)
    }

    @Test
    fun `does not add experience for an already reached target`() {
        val enhancement = service.calculateEnhancement(
            rarity = 5,
            currentLevel = 80,
            targetLevel = 70,
        )

        assertEquals(WeaponEnhancementRequirement.EMPTY, enhancement)
    }
}
