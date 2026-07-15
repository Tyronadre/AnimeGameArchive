package de.tyro.genshinapp.service

import de.tyro.genshinapp.entity.TravelerElementProgress
import de.tyro.genshinapp.entity.TravelerPreference
import de.tyro.genshinapp.entity.User
import de.tyro.genshinapp.model.CharacterProgress
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.TravelerAppearance
import de.tyro.genshinapp.model.TravelerElement
import de.tyro.genshinapp.repository.TravelerElementProgressRepository
import de.tyro.genshinapp.repository.TravelerPreferenceRepository
import de.tyro.genshinapp.repository.UserRepository
import java.util.Optional
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TravelerServiceTest {
    @Test
    fun `stores appearance and independent elemental progress`() {
        val preferences = mock(TravelerPreferenceRepository::class.java)
        val progress = mock(TravelerElementProgressRepository::class.java)
        val users = mock(UserRepository::class.java)
        val user = User().also { it.id = 7L }
        var storedPreference: TravelerPreference? = null
        val storedProgress = linkedMapOf<String, TravelerElementProgress>()

        `when`(users.findById(7L)).thenReturn(Optional.of(user))
        `when`(preferences.findByUser_Id(7L)).thenAnswer { storedPreference }
        `when`(preferences.save(any(TravelerPreference::class.java))).thenAnswer {
            (it.arguments[0] as TravelerPreference).also { saved -> storedPreference = saved }
        }
        `when`(progress.findByUser_IdAndElement(eq(7L), anyString())).thenAnswer {
            storedProgress[it.arguments[1] as String]
        }
        `when`(progress.save(any(TravelerElementProgress::class.java))).thenAnswer {
            (it.arguments[0] as TravelerElementProgress).also { saved ->
                storedProgress[saved.element] = saved
            }
        }
        val service = TravelerService(preferences, progress, users)

        assertFalse(service.selection(7L).elementConfigured)
        service.selectAppearance(7L, TravelerAppearance.LUMINE)
        service.selectElement(
            7L,
            TravelerElement.GEO,
            PlayerCharacterState("Traveler", 80, 4, 5, 6, 8, 9),
        )

        assertEquals(TravelerAppearance.LUMINE, service.selection(7L).appearance)
        assertEquals(TravelerElement.GEO, service.selection(7L).element)
        assertEquals(8, service.progress(7L, TravelerElement.GEO)?.skillTalent)

        service.selectElement(7L, TravelerElement.DENDRO, null)
        service.saveProgress(
            7L,
            TravelerElement.DENDRO,
            CharacterProgress(
                owned = true,
                level = 80,
                ascension = 5,
                constellation = 2,
                normalTalent = 3,
                skillTalent = 7,
                burstTalent = 6,
                targetLevel = 90,
                targetAscension = 6,
                targetNormalTalent = 6,
                targetSkillTalent = 9,
                targetBurstTalent = 9,
            ),
        )

        assertEquals(7, service.progress(7L, TravelerElement.DENDRO)?.skillTalent)
        assertEquals(8, service.progress(7L, TravelerElement.GEO)?.skillTalent)

        TravelerElement.entries.forEachIndexed { index, element ->
            service.selectElement(7L, element, null)
            service.saveProgress(
                7L,
                element,
                CharacterProgress(
                    owned = true,
                    level = 80,
                    ascension = 5,
                    constellation = index,
                    normalTalent = 1,
                    skillTalent = index + 2,
                    burstTalent = 1,
                    targetLevel = 90,
                    targetAscension = 6,
                    targetNormalTalent = 9,
                    targetSkillTalent = 10,
                    targetBurstTalent = 9,
                ),
            )
        }

        TravelerElement.entries.forEachIndexed { index, element ->
            assertEquals(index + 2, service.progress(7L, element)?.skillTalent)
            assertEquals(index, service.progress(7L, element)?.constellation)
        }
    }
}
