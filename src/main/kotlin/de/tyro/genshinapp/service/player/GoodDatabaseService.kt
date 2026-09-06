package de.tyro.genshinapp.service.player

import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.service.planning.CharacterTargetService
import org.springframework.stereotype.Service

@Service
class GoodDatabaseService(
    private val snapshotStore: PlayerSnapshotStore,
    private val exportService: GoodExportService,
    private val characterTargetService: CharacterTargetService,
    private val travelerService: TravelerService,
) {
    fun import(userId: Long, bytes: ByteArray): PlayerSnapshot {
        val snapshot = snapshotStore.save(userId, bytes)
        characterTargetService.clearCurrentState(userId)
        travelerService.replaceImportedProgress(userId, snapshot)
        return snapshot
    }

    fun export(userId: Long): ByteArray = exportService.export(userId)
}
