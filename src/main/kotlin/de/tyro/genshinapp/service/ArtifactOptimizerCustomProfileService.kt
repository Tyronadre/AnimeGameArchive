package de.tyro.genshinapp.service

import de.tyro.genshinapp.entity.ArtifactOptimizerCustomProfile
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.repository.ArtifactOptimizerCustomProfileRepository
import de.tyro.genshinapp.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ArtifactOptimizerCustomProfileService(
    private val repository: ArtifactOptimizerCustomProfileRepository,
    private val userRepository: UserRepository,
) {
    @Transactional(readOnly = true)
    fun findAll(userId: Long, characterKey: String): List<NamedArtifactOptimizerProfile> =
        repository.findAllByUser_IdAndCharacterKeyOrderByProfileNameAsc(
            userId,
            GoodKeyNormalizer.normalize(characterKey),
        ).map { it.toValues() }

    @Transactional(readOnly = true)
    fun find(
        userId: Long,
        characterKey: String,
        id: Long?,
    ): NamedArtifactOptimizerProfile? = id?.let {
        repository.findByIdAndUser_IdAndCharacterKey(
            it,
            userId,
            GoodKeyNormalizer.normalize(characterKey),
        )?.toValues()
    }

    @Transactional
    fun save(
        userId: Long,
        characterKey: String,
        id: Long?,
        name: String,
        profile: ArtifactOptimizationProfile,
        targets: ArtifactOptimizationTargets,
        setSelection: ArtifactSetSelection,
    ): NamedArtifactOptimizerProfile {
        val normalizedCharacterKey = GoodKeyNormalizer.normalize(characterKey)
        val safeName = name.trim().take(MAX_NAME_LENGTH)
        require(normalizedCharacterKey.isNotBlank() && safeName.isNotBlank())
        val entity = id?.let {
            repository.findByIdAndUser_IdAndCharacterKey(it, userId, normalizedCharacterKey)
        } ?: repository.findByUser_IdAndCharacterKeyAndProfileNameIgnoreCase(
            userId,
            normalizedCharacterKey,
            safeName,
        ) ?: ArtifactOptimizerCustomProfile().also {
            it.user = userRepository.findById(userId)
                .orElseThrow { IllegalArgumentException("User not found") }
            it.characterKey = normalizedCharacterKey
        }
        val requirements = setSelection.requirements.take(2)
        entity.profileName = safeName
        entity.baseProfileKey = profile.key
        entity.sandsMain = targets.mainStats["sands"]
        entity.gobletMain = targets.mainStats["goblet"]
        entity.circletMain = targets.mainStats["circlet"]
        entity.substatKeys = targets.substatPriorities.joinToString(",")
        entity.minimumTargets = encode(targets.minimumTargets)
        entity.maximumTargets = encode(targets.maximumTargets)
        entity.additionalStats = encode(targets.additionalStats)
        entity.setMode = setSelection.mode.key
        entity.firstSetKey = requirements.getOrNull(0)?.setKey
        entity.firstSetCount = requirements.getOrNull(0)?.count
        entity.secondSetKey = requirements.getOrNull(1)?.setKey
        entity.secondSetCount = requirements.getOrNull(1)?.count
        return repository.save(entity).toValues()
    }

    @Transactional
    fun delete(userId: Long, characterKey: String, id: Long) {
        repository.findByIdAndUser_IdAndCharacterKey(
            id,
            userId,
            GoodKeyNormalizer.normalize(characterKey),
        )?.let(repository::delete)
    }

    private fun ArtifactOptimizerCustomProfile.toValues(): NamedArtifactOptimizerProfile {
        val profile = ArtifactOptimizationProfile.fromKey(baseProfileKey)
        return NamedArtifactOptimizerProfile(
            id = requireNotNull(id),
            name = profileName,
            profile = profile,
            targets = ArtifactOptimizationTargets(
                custom = true,
                mainStats = mapOf(
                    "sands" to sandsMain,
                    "goblet" to gobletMain,
                    "circlet" to circletMain,
                ),
                substatPriorities = substatKeys.split(',').filter(String::isNotBlank),
                minimumTargets = decode(minimumTargets),
                maximumTargets = decode(maximumTargets)
                    .ifEmpty { mapOf("critRate_" to 100.0) },
                additionalStats = decode(additionalStats),
            ),
            setSelection = ArtifactSetSelection(
                mode = ArtifactSetSelectionMode.fromKey(setMode),
                requirements = listOfNotNull(
                    firstSetKey?.let { ArtifactSetTarget(it, firstSetCount ?: 2) },
                    secondSetKey?.let { ArtifactSetTarget(it, secondSetCount ?: 2) },
                ),
            ),
        )
    }

    private fun encode(values: Map<String, Double>): String =
        values.entries.joinToString(";") { (key, value) -> "$key:$value" }

    private fun decode(value: String): Map<String, Double> =
        value.split(';').mapNotNull { entry ->
            val key = entry.substringBefore(':')
            val number = entry.substringAfter(':', "").toDoubleOrNull()
            if (key.isBlank() || number == null || !number.isFinite()) null else key to number
        }.toMap()

    companion object {
        private const val MAX_NAME_LENGTH = 60
    }
}

data class NamedArtifactOptimizerProfile(
    val id: Long,
    val name: String,
    val profile: ArtifactOptimizationProfile,
    val targets: ArtifactOptimizationTargets,
    val setSelection: ArtifactSetSelection,
) {
    val selectionKey: String
        get() = "custom-$id"
}
