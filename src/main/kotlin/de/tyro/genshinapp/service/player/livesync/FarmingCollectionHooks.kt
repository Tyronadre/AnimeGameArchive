package de.tyro.genshinapp.service.player.livesync

import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.model.MaterialDefinition
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import de.tyro.genshinapp.service.player.SnapshotActivityEvent
import de.tyro.genshinapp.service.player.SnapshotActivityService
import de.tyro.genshinapp.service.player.SnapshotActivityType
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

@Component
class LeyLineCollectionHook(
    private val detector: FarmingCollectionDetector,
    private val activity: SnapshotActivityService,
) : LiveSyncHook {
    override fun onChange(change: LiveSyncChange) {
        detector.detect(change)?.takeIf { it.type in LEY_LINE_TYPES }
            ?.let { activity.recordEvents(change.userId, listOf(it)) }
    }

    companion object {
        val LEY_LINE_TYPES = setOf(SnapshotActivityType.MORA_LEY_LINE, SnapshotActivityType.EXPERIENCE_LEY_LINE)
    }
}

@Component
class DomainCollectionHook(
    private val detector: FarmingCollectionDetector,
    private val activity: SnapshotActivityService,
) : LiveSyncHook {
    override fun onChange(change: LiveSyncChange) {
        detector.detect(change)?.takeIf { it.type in DOMAIN_TYPES }
            ?.let { activity.recordEvents(change.userId, listOf(it)) }
    }

    companion object {
        val DOMAIN_TYPES = setOf(
            SnapshotActivityType.TALENT_DOMAIN, SnapshotActivityType.WEAPON_DOMAIN, SnapshotActivityType.ARTIFACT_DOMAIN,
        )
    }
}

/** Inventory evidence only: ambiguous mixed batches are deliberately not assigned to an activity. */
@Service
class FarmingCollectionDetector(private val catalog: MaterialCatalogService) {
    fun detect(change: LiveSyncChange): SnapshotActivityEvent? {
        val deltas = change.materials.associate { it.key to it.delta }
        fun delta(vararg keys: String): Long = keys.sumOf { deltas[it] ?: 0L }
        val mora = delta("mora", "202")
        val condensed = delta("condensedresin")
        val original = delta("originalresin")
        // Replenishment, purchases, event rewards, and combined claims cannot be attributed safely.
        if (condensed < -1 || original < -60 || delta("primogem", "201") > 0 ||
            delta("acquaintfate", "intertwinedfate", "fragileresin", "transientresin") > 0
        ) return null
        val resin = when {
            condensed == -1L && original >= 0 -> 60
            condensed == 0L && -original in setOf(20L, 40L, 60L) -> (-original).toInt()
            else -> null
        }
        val materials = catalog.getMaterials()
        fun materialDelta(material: MaterialDefinition) =
            delta(GoodKeyNormalizer.normalize(material.name), material.id.toString())
        if (materials.any {
                it.category in setOf(MaterialCategory.WEEKLY_BOSS, MaterialCategory.WORLD_BOSS, MaterialCategory.GEM) &&
                    materialDelta(it) > 0
            }) return null

        val domainMaterials = materials.filter {
            it.category in setOf(MaterialCategory.TALENT_BOOK, MaterialCategory.WEAPON_ASCENSION)
        }
        val gained = domainMaterials.filter { materialDelta(it) > 0 }
        val books = listOf(delta("wanderersadvice", "104001"), delta("adventurersexperience", "104002"), delta("heroswit", "104003"))
        val artifacts = addedArtifacts(change) ?: return null
        if (gained.isNotEmpty()) {
            if (books.any { it > 0 } || artifacts.isNotEmpty()) return null
            val family = gained.map { it.category to familyKey(it) }.distinct().singleOrNull()
                ?: return null
            if (domainMaterials.any {
                    it.category == family.first && familyKey(it) == family.second && materialDelta(it) < 0
                }) return null
            // Multiple tiers plus reward Mora distinguish a typical bundle from a single gifted book.
            if (resin == null && (gained.size < 2 || mora <= 0)) return null
            val talent = family.first == MaterialCategory.TALENT_BOOK
            return event(change, if (talent) SnapshotActivityType.TALENT_DOMAIN else SnapshotActivityType.WEAPON_DOMAIN,
                "${if (talent) "talent" else "weapon"}:${gained.first().craftingFamily ?: gained.first().id}", gained.first().name, resin)
        }
        if (artifacts.isNotEmpty()) {
            if (books.any { it > 0 } || mora <= 0 || artifacts.any { it.level != 0 || it.elixirCrafted }) return null
            if (artifacts.none { it.rarity == 5 && GoodKeyNormalizer.normalize(it.setKey) !in BOSS_ARTIFACT_SETS }) return null
            if (artifacts.any { it.rarity == 5 && GoodKeyNormalizer.normalize(it.setKey) in BOSS_ARTIFACT_SETS }) return null
            if (resin == null && artifacts.size < 2) return null
            // Snapshot data cannot identify the physical domain (or distinguish Stygian rewards).
            return event(change, SnapshotActivityType.ARTIFACT_DOMAIN, "artifacts:domain",
                artifacts.filter { it.rarity == 5 }.map { it.setName }.distinct().joinToString(", "), resin)
        }
        if (books.any { it > 0 }) {
            if (mora !in 0L..1_000L || books.any { it < 0 }) return null
            val matches = (1L..3L).any { multiplier -> XP_BUNDLES.any { bundle ->
                books.indices.all { index -> books[index] in bundle[index].first * multiplier..bundle[index].last * multiplier }
            } }
            if (!matches) return null
            return event(change, SnapshotActivityType.EXPERIENCE_LEY_LINE, "ley-line:experience", "", resin)
        }
        if ((1L..3L).any { multiplier -> mora in MORA_REWARDS.map { it * multiplier } }) {
            return event(change, SnapshotActivityType.MORA_LEY_LINE, "ley-line:mora", "", resin)
        }
        return null
    }

    private fun familyKey(material: MaterialDefinition): String = material.craftingFamily
        ?: if (material.category == MaterialCategory.WEAPON_ASCENSION && material.id in 114001..114999) {
            "weapon-family:${(material.id - 114001) / 4}"
        } else material.id.toString()

    private fun event(change: LiveSyncChange, type: SnapshotActivityType, taskKey: String, name: String, resin: Int?) =
        SnapshotActivityEvent(
            type = type, occurredAt = change.occurredAt, name = name,
            eventId = "${change.sessionId}:${change.current.revision}:${change.current.importedAt}:$type",
            taskKey = taskKey, resinCost = resin,
        )

    private fun addedArtifacts(change: LiveSyncChange): List<PlayerArtifact>? {
        fun identity(artifact: PlayerArtifact) = artifact.copy(location = null, locked = false)
        val remaining = change.current.artifacts.map(::identity).toMutableList()
        // Multiset subtraction preserves identical copies. Upgrading or consuming artifacts is not a reward claim.
        for (artifact in change.previous.artifacts) {
            if (!remaining.remove(identity(artifact))) return null
        }
        return remaining
    }

    companion object {
        private val BOSS_ARTIFACT_SETS = setOf("gladiatorsfinale", "wandererstroupe")
        private val MORA_REWARDS = listOf(12_000L, 20_000L, 28_000L, 36_000L, 44_000L, 52_000L, 60_000L)
        private val XP_BUNDLES = listOf(
            listOf(7L..8L, 3L..4L, 0L..0L), listOf(10L..12L, 5L..6L, 0L..0L),
            listOf(0L..0L, 10L..11L, 0L..0L), listOf(0L..0L, 13L..14L, 0L..0L),
            listOf(0L..0L, 6L..7L, 2L..3L), listOf(0L..0L, 6L..7L, 3L..4L),
            listOf(0L..0L, 6L..7L, 4L..5L),
        )
    }
}
