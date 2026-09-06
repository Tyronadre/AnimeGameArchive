package de.tyro.genshinapp.service.player

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.entity.PlayerArtifactInstance
import de.tyro.genshinapp.entity.PlayerArtifactInventory
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerArtifactStat
import de.tyro.genshinapp.repository.PlayerArtifactInstanceRepository
import de.tyro.genshinapp.repository.PlayerArtifactInventoryRepository
import de.tyro.genshinapp.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Authoritative owned artifacts; legacy snapshot/override files are a one-time migration source. */
@Service
class PlayerArtifactStorageService(
    private val repository: PlayerArtifactInstanceRepository,
    private val inventories: PlayerArtifactInventoryRepository,
    private val users: UserRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun initializeIfMissing(userId: Long, artifacts: List<PlayerArtifact>) {
        if (!inventories.existsById(userId)) replaceAll(userId, artifacts)
    }

    @Transactional(readOnly = true)
    fun findAll(userId: Long): List<PlayerArtifact> =
        repository.findAllByUser_IdOrderByImportPositionAsc(userId).map { row -> PlayerArtifact(
            setKey = row.setKey, slotKey = row.slotKey, level = row.level, rarity = row.rarity,
            mainStatKey = row.mainStatKey, location = row.location, locked = row.locked,
            substats = objectMapper.readValue(row.substatsJson, object : TypeReference<List<PlayerArtifactStat>>() {}),
            totalRolls = row.totalRolls, astralMark = row.astralMark, elixirCrafted = row.elixirCrafted,
            unactivatedSubstats = row.unactivatedSubstatsJson
                ?.takeIf(String::isNotBlank)
                ?.let {
                    objectMapper.readValue(
                        it,
                        object : TypeReference<List<PlayerArtifactStat>>() {},
                    )
                }
                .orEmpty(),
        ) }

    @Transactional
    fun replaceAll(userId: Long, artifacts: List<PlayerArtifact>) {
        require(userId > 0 && artifacts.size <= GoodImportService.MAX_ARTIFACTS) { "Invalid artifact inventory" }
        val user = users.findById(userId).orElseThrow { IllegalArgumentException("Unknown user id") }
        val stored = repository.findAllByUser_IdOrderByImportPositionAsc(userId).associateBy { it.importPosition }
        // Preserve row identities/order used by inventory assignment; update equipment in the same transaction.
        repository.saveAll(artifacts.mapIndexed { index, artifact ->
            (stored[index] ?: PlayerArtifactInstance().also { it.user = user; it.importPosition = index }).also { row ->
                row.setKey = artifact.setKey
                row.slotKey = artifact.slotKey
                row.level = artifact.level
                row.rarity = artifact.rarity
                row.mainStatKey = artifact.mainStatKey
                row.location = artifact.location
                row.locked = artifact.locked
                row.totalRolls = artifact.totalRolls
                row.astralMark = artifact.astralMark
                row.elixirCrafted = artifact.elixirCrafted
                row.substatsJson = objectMapper.writeValueAsString(artifact.substats.map {
                    linkedMapOf<String, Any>("key" to it.key, "value" to it.value).apply {
                        it.initialValue?.let { initialValue -> put("initialValue", initialValue) }
                    }
                })
                row.unactivatedSubstatsJson = objectMapper.writeValueAsString(
                    artifact.unactivatedSubstats.map {
                        linkedMapOf<String, Any>("key" to it.key, "value" to it.value).apply {
                            it.initialValue?.let { initialValue -> put("initialValue", initialValue) }
                        }
                    },
                )
            }
        })
        repository.deleteAll(stored.filterKeys { it >= artifacts.size }.values)
        if (!inventories.existsById(userId)) inventories.save(PlayerArtifactInventory().also { it.userId = userId })
    }
}
