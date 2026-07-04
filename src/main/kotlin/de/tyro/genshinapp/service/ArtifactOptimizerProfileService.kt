package de.tyro.genshinapp.service

import de.tyro.genshinapp.entity.ArtifactOptimizerProfile
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.repository.ArtifactOptimizerProfileRepository
import de.tyro.genshinapp.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ArtifactOptimizerProfileService(
    private val profileRepository: ArtifactOptimizerProfileRepository,
    private val userRepository: UserRepository,
) {
    @Transactional(readOnly = true)
    fun find(userId: Long, characterKey: String): SavedArtifactOptimizerProfile? =
        profileRepository.findByUser_IdAndCharacterKey(
            userId,
            GoodKeyNormalizer.normalize(characterKey),
        )?.toValues()

    @Transactional
    fun save(
        userId: Long,
        characterKey: String,
        profile: ArtifactOptimizationProfile,
        targets: ArtifactOptimizationTargets,
        setSelection: ArtifactSetSelection,
        customProfileId: Long? = null,
    ): SavedArtifactOptimizerProfile {
        val normalizedCharacterKey = GoodKeyNormalizer.normalize(characterKey)
        require(normalizedCharacterKey.isNotBlank()) { "Invalid character key" }

        val entity = profileRepository.findByUser_IdAndCharacterKey(
            userId,
            normalizedCharacterKey,
        ) ?: ArtifactOptimizerProfile().also {
            it.user = userRepository.findById(userId)
                .orElseThrow { IllegalArgumentException("User not found") }
            it.characterKey = normalizedCharacterKey
        }
        val requirements = setSelection.requirements.take(2)
        entity.profileKey = profile.key
        entity.customTargets = targets.custom
        entity.sandsMain = targets.mainStats["sands"]
        entity.gobletMain = targets.mainStats["goblet"]
        entity.circletMain = targets.mainStats["circlet"]
        entity.substatKeys = targets.substatPriorities.joinToString(",")
        entity.minimumTargets = encodeMinimumTargets(targets.minimumTargets)
        entity.maximumTargets = encodeTargets(targets.maximumTargets)
        entity.customProfileId = customProfileId
        entity.additionalCritRate = targets.additionalCritRate
        entity.additionalStats = encodeStats(targets.additionalStats)
        entity.setMode = setSelection.mode.key
        entity.firstSetKey = requirements.getOrNull(0)?.setKey
        entity.firstSetCount = requirements.getOrNull(0)?.count
        entity.secondSetKey = requirements.getOrNull(1)?.setKey
        entity.secondSetCount = requirements.getOrNull(1)?.count
        return profileRepository.save(entity).toValues()
    }

    @Transactional
    fun delete(userId: Long, characterKey: String) {
        profileRepository.findByUser_IdAndCharacterKey(
            userId,
            GoodKeyNormalizer.normalize(characterKey),
        )?.let(profileRepository::delete)
    }

    private fun ArtifactOptimizerProfile.toValues(): SavedArtifactOptimizerProfile {
        val profile = ArtifactOptimizationProfile.fromKey(profileKey)
        val storedAdditionalStats = parseStats(additionalStats).toMutableMap().also { stats ->
            if ("critRate_" !in stats && additionalCritRate != 0.0) {
                stats["critRate_"] = additionalCritRate
            }
        }
        val targets = if (customTargets) {
            ArtifactOptimizationTargets(
                custom = true,
                mainStats = mapOf(
                    "sands" to sandsMain,
                    "goblet" to gobletMain,
                    "circlet" to circletMain,
                ),
                substatPriorities = substatKeys.split(',')
                    .filter(String::isNotBlank)
                    .distinct()
                    .ifEmpty { ArtifactOptimizationTargets.defaultPriorities(profile) },
                minimumTargets = parseMinimumTargets(minimumTargets),
                maximumTargets = parseMaximumTargets(maximumTargets),
                additionalStats = storedAdditionalStats,
            )
        } else {
            ArtifactOptimizationTargets.defaults(profile).copy(
                additionalStats = storedAdditionalStats,
            )
        }
        val requirements = listOfNotNull(
            firstSetKey?.let { ArtifactSetTarget(it, firstSetCount ?: 2) },
            secondSetKey?.let { ArtifactSetTarget(it, secondSetCount ?: 2) },
        )
        return SavedArtifactOptimizerProfile(
            profile = profile,
            targets = targets,
            setSelection = ArtifactSetSelection(
                mode = ArtifactSetSelectionMode.fromKey(setMode),
                requirements = requirements,
            ),
            customProfileId = customProfileId,
        )
    }

    private fun encodeMinimumTargets(targets: Map<String, Double>): String =
        encodeTargets(targets)

    private fun encodeTargets(targets: Map<String, Double>): String =
        targets.entries.joinToString(";") { (key, value) -> "$key:$value" }

    private fun parseMinimumTargets(value: String): Map<String, Double> =
        value.split(';').mapNotNull { entry ->
            val key = entry.substringBefore(':')
            val target = entry.substringAfter(':', "").toDoubleOrNull()
            if (key.isBlank() || target == null || target <= 0.0) null else key to target
        }.toMap()

    private fun parseMaximumTargets(value: String): Map<String, Double> =
        parseMinimumTargets(value).ifEmpty { mapOf("critRate_" to 100.0) }

    private fun encodeStats(stats: Map<String, Double>): String =
        stats.entries.joinToString(";") { (key, value) -> "$key:$value" }

    private fun parseStats(value: String): Map<String, Double> =
        value.split(';').mapNotNull { entry ->
            val key = entry.substringBefore(':')
            val statValue = entry.substringAfter(':', "").toDoubleOrNull()
            if (key.isBlank() || statValue == null || !statValue.isFinite() || statValue == 0.0) {
                null
            } else {
                key to statValue
            }
        }.toMap()
}

data class SavedArtifactOptimizerProfile(
    val profile: ArtifactOptimizationProfile,
    val targets: ArtifactOptimizationTargets,
    val setSelection: ArtifactSetSelection,
    val customProfileId: Long?,
)
