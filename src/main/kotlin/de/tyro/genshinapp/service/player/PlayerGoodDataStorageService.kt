package de.tyro.genshinapp.service.player

import de.tyro.genshinapp.entity.PlayerGoodCharacterEntity
import de.tyro.genshinapp.entity.PlayerGoodMaterialEntity
import de.tyro.genshinapp.entity.PlayerGoodSnapshotEntity
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.repository.PlayerGoodCharacterRepository
import de.tyro.genshinapp.repository.PlayerGoodMaterialRepository
import de.tyro.genshinapp.repository.PlayerGoodSnapshotRepository
import de.tyro.genshinapp.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PlayerGoodDataStorageService(
    private val snapshots: PlayerGoodSnapshotRepository,
    private val characters: PlayerGoodCharacterRepository,
    private val materials: PlayerGoodMaterialRepository,
    private val users: UserRepository,
    private val artifactStorage: PlayerArtifactStorageService,
    private val weaponStorage: PlayerWeaponService,
) {
    @Transactional
    fun replaceAll(userId: Long, snapshot: PlayerSnapshot) {
        val user = users.findById(userId)
            .orElseThrow { IllegalArgumentException("Unknown user id") }

        characters.deleteAllForUser(userId)
        materials.deleteAllForUser(userId)
        snapshots.save(
            (snapshots.findById(userId).orElseGet(::PlayerGoodSnapshotEntity)).also {
                it.userId = userId
                it.formatVersion = snapshot.formatVersion
                it.source = snapshot.source
                it.importedAt = snapshot.importedAt
            },
        )
        characters.saveAll(
            snapshot.characters.mapIndexed { index, state ->
                PlayerGoodCharacterEntity().also {
                    it.user = user
                    it.importPosition = index
                    it.exportKey = state.key
                    it.level = state.level
                    it.constellation = state.constellation
                    it.ascension = state.ascension
                    it.normalTalent = state.normalTalent
                    it.skillTalent = state.skillTalent
                    it.burstTalent = state.burstTalent
                }
            },
        )
        materials.saveAll(
            snapshot.inventory.entries.mapIndexed { index, (key, amount) ->
                PlayerGoodMaterialEntity().also {
                    it.user = user
                    it.importPosition = index
                    it.materialKey = key
                    it.exportKey = snapshot.inventoryNames[key] ?: key
                    it.amount = amount
                }
            },
        )
        artifactStorage.replaceAll(userId, snapshot.artifacts)
        weaponStorage.replaceAll(userId, snapshot.weapons)
    }

    @Transactional(readOnly = true)
    fun find(userId: Long): PlayerSnapshot? {
        val metadata = snapshots.findById(userId).orElse(null) ?: return null
        val storedCharacters = characters.findAllByUser_IdOrderByImportPositionAsc(userId)
        val storedMaterials = materials.findAllByUser_IdOrderByImportPositionAsc(userId)
        return PlayerSnapshot(
            formatVersion = metadata.formatVersion,
            source = metadata.source,
            importedAt = metadata.importedAt,
            characters = storedCharacters.map {
                PlayerCharacterState(
                    key = it.exportKey,
                    level = it.level,
                    constellation = it.constellation,
                    ascension = it.ascension,
                    normalTalent = it.normalTalent,
                    skillTalent = it.skillTalent,
                    burstTalent = it.burstTalent,
                )
            },
            inventory = storedMaterials.associate { it.materialKey to it.amount },
            inventoryNames = storedMaterials.associate { it.materialKey to it.exportKey },
            exportedInventoryKeys = storedMaterials.size,
            artifacts = emptyList(),
            weapons = emptyList(),
        )
    }

    @Transactional
    fun updateMaterial(userId: Long, key: String, amount: Long, exportKey: String) {
        val existing = materials.findByUser_IdAndMaterialKey(userId, key)
        if (existing != null) {
            existing.amount = amount
            return
        }
        val user = users.findById(userId)
            .orElseThrow { IllegalArgumentException("Unknown user id") }
        val nextPosition = materials.findAllByUser_IdOrderByImportPositionAsc(userId)
            .maxOfOrNull { it.importPosition + 1 }
            ?: 0
        materials.save(
            PlayerGoodMaterialEntity().also {
                it.user = user
                it.importPosition = nextPosition
                it.materialKey = key
                it.exportKey = exportKey
                it.amount = amount
            },
        )
    }
}
