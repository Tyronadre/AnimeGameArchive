package de.tyro.genshinapp.service.player.livesync

import de.tyro.genshinapp.model.PlayerSnapshot
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/** Register a Spring bean implementing this interface to observe saved live changes.
 * Hooks run synchronously in Spring order; failures are isolated from capture and other hooks.
 */
fun interface LiveSyncHook {
    fun onChange(change: LiveSyncChange)
}

data class LiveSyncMaterialChange(
    val key: String,
    val previousAmount: Long,
    val currentAmount: Long,
) {
    val delta: Long get() = currentAmount - previousAmount
}

/** Full before/after state is available for hooks concerning equipment or characters too. */
data class LiveSyncChange(
    val userId: Long,
    val sessionId: String,
    val previous: PlayerSnapshot,
    val current: PlayerSnapshot,
    val occurredAt: Instant = Instant.now(),
) {
    val materials: List<LiveSyncMaterialChange> =
        (previous.inventory.keys + current.inventory.keys).mapNotNull { key ->
            val before = previous.inventory[key] ?: 0L
            val after = current.inventory[key] ?: 0L
            if (before == after) null else LiveSyncMaterialChange(key, before, after)
        }

    val hasChanges: Boolean
        get() = materials.isNotEmpty() || previous.characters != current.characters ||
            previous.artifacts != current.artifacts || previous.weapons != current.weapons
}

@Service
class LiveSyncHookDispatcher(private val hooks: List<LiveSyncHook>) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun dispatch(change: LiveSyncChange) {
        if (!change.hasChanges) return
        hooks.forEach { hook ->
            try {
                hook.onChange(change)
            } catch (exception: Exception) {
                logger.warn("Live sync hook {} failed for user {}", hook.javaClass.name, change.userId, exception)
            }
        }
    }
}
