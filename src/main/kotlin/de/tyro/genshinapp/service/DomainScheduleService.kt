package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.MaterialSchedule
import org.springframework.stereotype.Service
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

@Service
class DomainScheduleService {
    fun isFarmable(
        schedule: MaterialSchedule,
        date: LocalDate = currentGameDate(),
    ): Boolean = isFarmable(schedule.ordinal, date.dayOfWeek)

    fun isTalentBookFarmable(
        materialId: Int,
        date: LocalDate = currentGameDate(),
    ): Boolean = isFarmable(rotationForTalentBook(materialId), date.dayOfWeek)

    fun isWeaponMaterialFarmable(
        materialId: Int,
        date: LocalDate = currentGameDate(),
    ): Boolean = isFarmable(rotationForWeaponMaterial(materialId), date.dayOfWeek)

    fun talentBookDays(materialId: Int): Set<DayOfWeek> =
        daysFor(rotationForTalentBook(materialId))

    fun weaponMaterialDays(materialId: Int): Set<DayOfWeek> =
        daysFor(rotationForWeaponMaterial(materialId))

    private fun rotationForTalentBook(materialId: Int): Int? =
        materialId.takeIf { it in TALENT_BOOK_RANGE }
            ?.let { ((it - TALENT_BOOK_RANGE.first) / TALENT_FAMILY_SIZE) % ROTATION_SIZE }

    private fun rotationForWeaponMaterial(materialId: Int): Int? =
        materialId.takeIf { it in WEAPON_MATERIAL_RANGE }
            ?.let { ((it - WEAPON_MATERIAL_RANGE.first) / WEAPON_FAMILY_SIZE) % ROTATION_SIZE }

    private fun isFarmable(rotation: Int?, day: DayOfWeek): Boolean =
        day == DayOfWeek.SUNDAY || day in daysFor(rotation)

    private fun daysFor(rotation: Int?): Set<DayOfWeek> = when (rotation) {
        0 -> setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY, DayOfWeek.SUNDAY)
        1 -> setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY)
        2 -> setOf(DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        else -> emptySet()
    }

    fun currentGameDate(
        now: ZonedDateTime = ZonedDateTime.now(FARMING_ZONE),
    ): LocalDate = now.withZoneSameInstant(FARMING_ZONE)
        .minusHours(DAILY_RESET_HOUR.toLong())
        .toLocalDate()

    companion object {
        private val FARMING_ZONE = ZoneOffset.ofHours(1)
        private val TALENT_BOOK_RANGE = 104301..104999
        private val WEAPON_MATERIAL_RANGE = 114001..114999
        private const val TALENT_FAMILY_SIZE = 3
        private const val WEAPON_FAMILY_SIZE = 4
        private const val ROTATION_SIZE = 3
        private const val DAILY_RESET_HOUR = 4
    }
}
