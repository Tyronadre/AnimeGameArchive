package de.tyro.genshinapp.service

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainScheduleServiceTest {
    private val service = DomainScheduleService()

    @Test
    fun `talent books follow their weekday rotation and all open on Sunday`() {
        val monday = LocalDate.of(2026, 6, 29)
        val tuesday = monday.plusDays(1)
        val wednesday = monday.plusDays(2)
        val sunday = monday.plusDays(6)

        assertTrue(service.isTalentBookFarmable(104301, monday))
        assertFalse(service.isTalentBookFarmable(104301, tuesday))
        assertTrue(service.isTalentBookFarmable(104304, tuesday))
        assertTrue(service.isTalentBookFarmable(104307, wednesday))
        assertTrue(service.isTalentBookFarmable(104301, sunday))
        assertTrue(service.isTalentBookFarmable(104304, sunday))
        assertTrue(service.isTalentBookFarmable(104307, sunday))
    }

    @Test
    fun `weapon material families use the same three day rotation`() {
        assertTrue(
            DayOfWeek.THURSDAY in service.weaponMaterialDays(114013),
        )
        assertFalse(
            DayOfWeek.THURSDAY in service.weaponMaterialDays(114005),
        )
        assertTrue(
            service.isWeaponMaterialFarmable(
                114005,
                LocalDate.of(2026, 7, 5),
            ),
        )
    }

    @Test
    fun `the current game day follows the fixed Europe server reset`() {
        val zone = ZoneId.of("Europe/Berlin")
        assertTrue(
            service.currentGameDate(
                ZonedDateTime.of(2026, 7, 2, 4, 59, 0, 0, zone),
            ) == LocalDate.of(2026, 7, 1),
        )
        assertTrue(
            service.currentGameDate(
                ZonedDateTime.of(2026, 7, 2, 5, 0, 0, 0, zone),
            ) == LocalDate.of(2026, 7, 2),
        )
    }
}
