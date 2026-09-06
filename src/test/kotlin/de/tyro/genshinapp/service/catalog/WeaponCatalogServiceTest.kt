package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.model.FightProperty
import de.tyro.genshinapp.service.importer.HoyolabWeaponSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WeaponCatalogServiceTest {
    @Test
    fun `builds the weapon catalogue from HoYoWiki summaries`() {
        val service = WeaponCatalogService()
        assertEquals(
            1,
            service.synchronizeHoyolabWeaponList(
                listOf(
                    HoyolabWeaponSummary(
                        1954,
                        "Skyward Blade",
                        "https://act-webstatic.hoyoverse.com/skyward-blade.png",
                        rarity = 5,
                        weaponType = "Sword",
                        secondaryStatType = FightProperty.FIGHT_PROP_CHARGE_EFFICIENCY,
                    ),
                ),
            ),
        )
        val weapon = assertNotNull(service.find("skywardblade"))
        assertEquals("Sword", weapon.weaponType)
        assertEquals(1954, weapon.hoyolabEntryId)
    }
}
