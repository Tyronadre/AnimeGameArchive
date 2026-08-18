package de.tyro.genshinapp.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WeaponDataServiceTest {
    @Test
    fun `imports complete weapon records from static genshin-db data idempotently`() {
        val service = WeaponDataService(mock(DynamicContentLoader::class.java))
        val weapon = jacksonObjectMapper().readTree(
            """
                {
                  "name":"Remote Blade",
                  "rarity":5,
                  "weaponText":"Sword",
                  "mainStatType":"FIGHT_PROP_CRITICAL",
                  "baseAtkValue":48,
                  "baseStatText":"4.8%",
                  "description":"A remotely imported sword.",
                  "costs":{}
                }
            """.trimIndent(),
        )

        assertEquals(1, service.importFromStaticData(listOf(weapon)))
        assertEquals(0, service.importFromStaticData(listOf(weapon)))
        val imported = assertNotNull(service.find("RemoteBlade"))
        assertEquals("Sword", imported.weaponType)
        assertEquals(48.0, imported.baseAttack)
        assertEquals(4.8, imported.baseSecondaryStat)
    }
}
