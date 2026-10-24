package de.tyro.genshinapp.service.player.livesync

import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.MaterialSourceRole
import de.tyro.genshinapp.model.MaterialSourceType
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import de.tyro.genshinapp.service.player.SnapshotActivityEvent
import de.tyro.genshinapp.service.player.SnapshotActivityService
import de.tyro.genshinapp.service.player.SnapshotActivityType
import org.springframework.stereotype.Component

@Component
class WeeklyBossCollectionHook(
    private val materialCatalog: MaterialCatalogService,
    private val activityService: SnapshotActivityService,
) : LiveSyncHook {
    override fun onChange(change: LiveSyncChange) {
        if (change.materials.none { it.delta > 0 }) return
        val events = materialCatalog.getSources(listOf(MaterialSourceType.WEEKLY_BOSS))
            .mapNotNull { boss ->
                // Gems are shared with other activities and cannot identify a weekly boss.
                val keys = boss.materials.filter { it.role == MaterialSourceRole.DROP }
                    .flatMap { listOf(GoodKeyNormalizer.normalize(it.material.name), it.material.id.toString()) }
                    .toSet()
                val drops = change.materials.filter { it.key in keys }
                // Converting drops with Dream Solvent does not increase the family's total.
                val netGain = drops.sumOf { it.delta }
                if (netGain <= 0) return@mapNotNull null
                SnapshotActivityEvent(
                    type = SnapshotActivityType.WEEKLY_BOSS_COLLECTED,
                    occurredAt = change.occurredAt,
                    name = boss.name,
                    amount = netGain,
                )
            }
        activityService.recordEvents(change.userId, events)
    }
}
