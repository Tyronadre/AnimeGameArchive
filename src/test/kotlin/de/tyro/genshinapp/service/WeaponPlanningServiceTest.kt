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
}
