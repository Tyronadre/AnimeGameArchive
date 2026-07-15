package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.TravelerIdentity
import de.tyro.genshinapp.model.PlayerArtifactStat
import de.tyro.genshinapp.model.PlayerSnapshot
import org.springframework.stereotype.Service

@Service
class PlayerArtifactManagementService(
    private val snapshotStore: PlayerSnapshotStore,
    private val artifactCatalogService: ArtifactCatalogService,
    private val artifactOptimizationService: ArtifactOptimizationService,
) {
    fun create(userId: Long, request: ArtifactMutationRequest): PlayerSnapshot {
        val artifact = validatedArtifact(request)
        return snapshotStore.updateArtifacts(userId) { artifacts -> artifacts + artifact }
    }

    fun update(
        userId: Long,
        artifactIndex: Int,
        request: ArtifactMutationRequest,
    ): PlayerSnapshot = snapshotStore.updateArtifacts(userId) { artifacts ->
        require(artifactIndex in artifacts.indices) { "Artifact not found" }
        val current = artifacts[artifactIndex]
        val updatedArtifact = validatedArtifact(request).copy(
                location = current.location,
            )
        artifacts.toMutableList().also { updated ->
            updated[artifactIndex] = updatedArtifact
            if (updatedArtifact.location != null) {
                artifacts.indices.firstOrNull { index ->
                    index != artifactIndex &&
                        artifacts[index].slotKey.equals(updatedArtifact.slotKey, ignoreCase = true) &&
                        TravelerIdentity.canonicalCharacterKey(
                            artifacts[index].location.orEmpty(),
                        ) == TravelerIdentity.canonicalCharacterKey(updatedArtifact.location)
                }?.let { collisionIndex ->
                    updated[collisionIndex] = artifacts[collisionIndex].copy(location = null)
                }
            }
        }
    }

    fun assign(
        userId: Long,
        artifactIndex: Int,
        requestedCharacterKey: String?,
    ): ArtifactAssignmentResult {
        var result = ArtifactAssignmentResult(null, false)
        snapshotStore.updateArtifacts(userId) { artifacts ->
            require(artifactIndex in artifacts.indices) { "Artifact not found" }
            val snapshot = snapshotStore.current(userId)
                ?: throw IllegalStateException("No GOOD file imported")
            val targetCharacter = requestedCharacterKey
                ?.takeIf(String::isNotBlank)
                ?.let { requested ->
                    snapshot.characters.firstOrNull {
                        GoodKeyNormalizer.normalize(it.key) ==
                            GoodKeyNormalizer.normalize(requested)
                    }?.key ?: throw IllegalArgumentException("Character not found")
                }
            val selected = artifacts[artifactIndex]
            val oldLocation = selected.location
            if (
                GoodKeyNormalizer.normalize(oldLocation.orEmpty()) ==
                GoodKeyNormalizer.normalize(targetCharacter.orEmpty())
            ) {
                result = ArtifactAssignmentResult(targetCharacter, false)
                return@updateArtifacts artifacts
            }

            val updated = artifacts.toMutableList()
            val replacedIndex = targetCharacter?.let { target ->
                artifacts.indices.firstOrNull { index ->
                    index != artifactIndex &&
                        artifacts[index].slotKey.equals(selected.slotKey, ignoreCase = true) &&
                        TravelerIdentity.canonicalCharacterKey(
                            artifacts[index].location.orEmpty(),
                        ) == TravelerIdentity.canonicalCharacterKey(target)
                }
            }
            updated[artifactIndex] = selected.copy(location = targetCharacter)
            if (replacedIndex != null) {
                updated[replacedIndex] = artifacts[replacedIndex].copy(
                    location = oldLocation?.takeIf(String::isNotBlank),
                )
            }
            result = ArtifactAssignmentResult(
                characterKey = targetCharacter,
                swapped = replacedIndex != null,
            )
            updated
        }
        return result
    }

    private fun validatedArtifact(request: ArtifactMutationRequest): PlayerArtifact {
        val setKey = GoodKeyNormalizer.normalize(request.setKey)
        require(setKey in artifactCatalogService.allSets()) { "Invalid artifact set" }
        val slotKey = request.slotKey.lowercase()
        require(slotKey in SLOT_KEYS) { "Invalid artifact slot" }
        require(request.rarity in 1..5) { "Invalid artifact rarity" }
        val maximumLevel = MAX_LEVEL_BY_RARITY.getValue(request.rarity)
        require(request.level in 0..maximumLevel) { "Invalid artifact level" }
        val validMainStats = artifactOptimizationService.mainStatOptions(slotKey)
            .mapTo(mutableSetOf()) { it.key }
        require(request.mainStatKey in validMainStats) { "Invalid artifact main stat" }

        val validSubstats = artifactOptimizationService.substatOptions()
            .mapTo(mutableSetOf()) { it.key }
        val substats = request.substats
            .filter { it.key.isNotBlank() }
            .also { stats ->
                require(stats.size <= 4) { "An artifact can have at most four substats" }
                require(stats.map(ArtifactStatInput::key).distinct().size == stats.size) {
                    "Artifact substats must be unique"
                }
                require(stats.none { it.key == request.mainStatKey }) {
                    "Main stat cannot also be a substat"
                }
                require(stats.all {
                    it.key in validSubstats &&
                        it.value.isFinite() &&
                        it.value >= 0.0 &&
                        it.value <= MAX_STAT_VALUE
                }) { "Invalid artifact substat" }
            }
            .map { PlayerArtifactStat(it.key, it.value) }
        val maximumRolls = minOf(9, 4 + request.level / 4)
        request.totalRolls?.let { totalRolls ->
            require(totalRolls in substats.size..maximumRolls) {
                "Invalid artifact roll count"
            }
        }

        return PlayerArtifact(
            setKey = setKey,
            slotKey = slotKey,
            level = request.level,
            rarity = request.rarity,
            mainStatKey = request.mainStatKey,
            location = null,
            locked = request.locked,
            substats = substats,
            totalRolls = request.totalRolls,
            astralMark = request.astralMark,
            elixirCrafted = request.elixirCrafted,
        )
    }

    companion object {
        private const val MAX_STAT_VALUE = 100_000.0
        private val SLOT_KEYS = setOf("flower", "plume", "sands", "goblet", "circlet")
        private val MAX_LEVEL_BY_RARITY = mapOf(
            1 to 4,
            2 to 4,
            3 to 12,
            4 to 16,
            5 to 20,
        )
    }
}

data class ArtifactMutationRequest(
    val setKey: String,
    val slotKey: String,
    val level: Int,
    val rarity: Int,
    val mainStatKey: String,
    val locked: Boolean,
    val astralMark: Boolean,
    val elixirCrafted: Boolean,
    val substats: List<ArtifactStatInput>,
    val totalRolls: Int? = null,
)

data class ArtifactStatInput(
    val key: String,
    val value: Double,
)

data class ArtifactAssignmentResult(
    val characterKey: String?,
    val swapped: Boolean,
)
