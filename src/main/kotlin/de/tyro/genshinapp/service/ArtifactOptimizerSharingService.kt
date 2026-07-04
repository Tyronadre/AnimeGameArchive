package de.tyro.genshinapp.service

import de.tyro.genshinapp.entity.SharedArtifactOptimizerProfile
import de.tyro.genshinapp.repository.SharedArtifactOptimizerProfileRepository
import de.tyro.genshinapp.repository.UserRepository
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ArtifactOptimizerSharingService(
    private val shareRepository: SharedArtifactOptimizerProfileRepository,
    private val userRepository: UserRepository,
) {
    @Transactional
    fun create(
        userId: Long,
        profile: ArtifactOptimizationProfile,
        targets: ArtifactOptimizationTargets,
        setSelection: ArtifactSetSelection,
    ): SharedArtifactOptimizerConfiguration {
        val requirements = setSelection.requirements.take(2)
        val entity = SharedArtifactOptimizerProfile().also {
            it.createdBy = userRepository.findById(userId)
                .orElseThrow { IllegalArgumentException("User not found") }
            it.shareToken = UUID.randomUUID().toString().replace("-", "")
            it.createdAt = Instant.now()
            it.profileKey = profile.key
            it.customTargets = targets.custom
            it.sandsMain = targets.mainStats["sands"]
            it.gobletMain = targets.mainStats["goblet"]
            it.circletMain = targets.mainStats["circlet"]
            it.substatKeys = targets.substatPriorities.joinToString(",")
            it.minimumTargets = encodeMinimumTargets(targets.minimumTargets)
            it.maximumTargets = encodeTargets(targets.maximumTargets)
            it.additionalCritRate = targets.additionalCritRate
            it.additionalStats = encodeStats(targets.additionalStats)
            it.setMode = setSelection.mode.key
            it.firstSetKey = requirements.getOrNull(0)?.setKey
            it.firstSetCount = requirements.getOrNull(0)?.count
            it.secondSetKey = requirements.getOrNull(1)?.setKey
            it.secondSetCount = requirements.getOrNull(1)?.count
        }
        return shareRepository.save(entity).toConfiguration()
    }

    @Transactional(readOnly = true)
    fun find(token: String?): SharedArtifactOptimizerConfiguration? {
        val safeToken = token
            ?.takeIf { it.matches(TOKEN_PATTERN) }
            ?: return null
        return shareRepository.findByShareToken(safeToken)?.toConfiguration()
    }

    private fun SharedArtifactOptimizerProfile.toConfiguration():
        SharedArtifactOptimizerConfiguration {
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
        return SharedArtifactOptimizerConfiguration(
            token = shareToken,
            profile = profile,
            targets = targets,
            setSelection = ArtifactSetSelection(
                mode = ArtifactSetSelectionMode.fromKey(setMode),
                requirements = listOfNotNull(
                    firstSetKey?.let { ArtifactSetTarget(it, firstSetCount ?: 2) },
                    secondSetKey?.let { ArtifactSetTarget(it, secondSetCount ?: 2) },
                ),
            ),
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

    companion object {
        private val TOKEN_PATTERN = Regex("[a-f0-9]{32}")
    }
}

data class SharedArtifactOptimizerConfiguration(
    val token: String,
    val profile: ArtifactOptimizationProfile,
    val targets: ArtifactOptimizationTargets,
    val setSelection: ArtifactSetSelection,
)
