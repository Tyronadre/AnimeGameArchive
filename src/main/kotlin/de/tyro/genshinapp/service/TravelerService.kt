package de.tyro.genshinapp.service

import de.tyro.genshinapp.entity.TravelerElementProgress
import de.tyro.genshinapp.entity.TravelerPreference
import de.tyro.genshinapp.model.CharacterProgress
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.TravelerAppearance
import de.tyro.genshinapp.model.TravelerElement
import de.tyro.genshinapp.model.TravelerElementProgress as TravelerElementProgressValues
import de.tyro.genshinapp.model.TravelerIdentity
import de.tyro.genshinapp.model.TravelerSelection
import de.tyro.genshinapp.repository.TravelerElementProgressRepository
import de.tyro.genshinapp.repository.TravelerPreferenceRepository
import de.tyro.genshinapp.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TravelerService(
    private val preferenceRepository: TravelerPreferenceRepository,
    private val progressRepository: TravelerElementProgressRepository,
    private val userRepository: UserRepository,
) {
    @Transactional(readOnly = true)
    fun selection(userId: Long): TravelerSelection {
        val preference = preferenceRepository.findByUser_Id(userId)
        val storedElement = TravelerElement.fromKey(preference?.activeElement)
        return TravelerSelection(
            appearance = TravelerAppearance.fromKey(preference?.appearance)
                ?: TravelerAppearance.AETHER,
            element = storedElement ?: TravelerElement.ANEMO,
            elementConfigured = storedElement != null,
        )
    }

    @Transactional
    fun selectAppearance(userId: Long, appearance: TravelerAppearance): TravelerSelection {
        val preference = findOrCreatePreference(userId)
        preference.appearance = appearance.key
        preferenceRepository.save(preference)
        return selectionOf(preference)
    }

    @Transactional
    fun selectElement(
        userId: Long,
        element: TravelerElement,
        importedState: PlayerCharacterState?,
    ): TravelerSelection {
        val preference = findOrCreatePreference(userId)
        val firstSelection = TravelerElement.fromKey(preference.activeElement) == null
        preference.activeElement = element.key
        preferenceRepository.save(preference)
        if (firstSelection && importedState != null) {
            saveImportedProgress(userId, element, importedState)
        }
        return selectionOf(preference)
    }

    @Transactional(readOnly = true)
    fun progress(userId: Long, element: TravelerElement): TravelerElementProgressValues? =
        progressRepository.findByUser_IdAndElement(userId, element.key)?.toValues()

    @Transactional
    fun saveProgress(
        userId: Long,
        element: TravelerElement,
        progress: CharacterProgress,
    ): TravelerElementProgressValues {
        val entity = findOrCreateProgress(userId, element)
        entity.currentConstellation = progress.constellation
        entity.currentNormalTalent = progress.normalTalent
        entity.currentSkillTalent = progress.skillTalent
        entity.currentBurstTalent = progress.burstTalent
        entity.targetNormalTalent = progress.targetNormalTalent
        entity.targetSkillTalent = progress.targetSkillTalent
        entity.targetBurstTalent = progress.targetBurstTalent
        return progressRepository.save(entity).toValues()
    }

    @Transactional
    fun importSnapshot(userId: Long, snapshot: PlayerSnapshot) {
        val element = TravelerElement.fromKey(
            preferenceRepository.findByUser_Id(userId)?.activeElement,
        ) ?: return
        val state = snapshot.characters.firstOrNull { TravelerIdentity.isTraveler(it.key) } ?: return
        saveImportedProgress(userId, element, state)
    }

    private fun saveImportedProgress(
        userId: Long,
        element: TravelerElement,
        state: PlayerCharacterState,
    ) {
        val entity = findOrCreateProgress(userId, element)
        entity.currentConstellation = state.constellation
        entity.currentNormalTalent = state.normalTalent
        entity.currentSkillTalent = state.skillTalent
        entity.currentBurstTalent = state.burstTalent
        entity.targetNormalTalent = maxOf(entity.targetNormalTalent, state.normalTalent)
        entity.targetSkillTalent = maxOf(entity.targetSkillTalent, state.skillTalent)
        entity.targetBurstTalent = maxOf(entity.targetBurstTalent, state.burstTalent)
        progressRepository.save(entity)
    }

    private fun findOrCreatePreference(userId: Long): TravelerPreference =
        preferenceRepository.findByUser_Id(userId)
            ?: TravelerPreference().also {
                it.user = findUser(userId)
            }

    private fun findOrCreateProgress(
        userId: Long,
        element: TravelerElement,
    ): TravelerElementProgress =
        progressRepository.findByUser_IdAndElement(userId, element.key)
            ?: TravelerElementProgress().also {
                it.user = findUser(userId)
                it.element = element.key
            }

    private fun findUser(userId: Long) = userRepository.findById(userId)
        .orElseThrow { IllegalArgumentException("User not found") }

    private fun selectionOf(preference: TravelerPreference): TravelerSelection {
        val element = TravelerElement.fromKey(preference.activeElement)
        return TravelerSelection(
            appearance = TravelerAppearance.fromKey(preference.appearance)
                ?: TravelerAppearance.AETHER,
            element = element ?: TravelerElement.ANEMO,
            elementConfigured = element != null,
        )
    }

    private fun TravelerElementProgress.toValues() = TravelerElementProgressValues(
        constellation = currentConstellation,
        normalTalent = currentNormalTalent,
        skillTalent = currentSkillTalent,
        burstTalent = currentBurstTalent,
        targetNormalTalent = targetNormalTalent,
        targetSkillTalent = targetSkillTalent,
        targetBurstTalent = targetBurstTalent,
    )
}
