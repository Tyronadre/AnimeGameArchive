package de.tyro.genshinapp.service.player

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.PlayerWeapon
import de.tyro.genshinapp.service.planning.DomainScheduleService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap

enum class SnapshotActivityType(
    val icon: String,
    val tone: String,
) {
    MATERIAL_GAIN("＋", "gain"),
    MATERIAL_SPEND("−", "spend"),
    WEEKLY_BOSS_COLLECTED("★", "gain"),
    MORA_LEY_LINE("₥", "gain"),
    EXPERIENCE_LEY_LINE("✦", "gain"),
    TALENT_DOMAIN("◆", "gain"),
    WEAPON_DOMAIN("◇", "gain"),
    ARTIFACT_DOMAIN("✧", "gain"),
    ARTIFACT_LEVEL("✦", "upgrade"),
    ARTIFACT_ADDED("◆", "new"),
    ARTIFACTS_REMOVED("◇", "removed"),
    WEAPON_LEVEL("↑", "upgrade"),
    WEAPON_ADDED("†", "new"),
    WEAPON_REMOVED("◇", "removed"),
    CHARACTER_LEVEL("★", "upgrade"),
}

data class SnapshotActivityEvent(
    val type: SnapshotActivityType = SnapshotActivityType.MATERIAL_GAIN,
    val occurredAt: Instant = Instant.EPOCH,
    val name: String = "",
    val materialKey: String? = null,
    val detailName: String? = null,
    val amount: Long? = null,
    val total: Long? = null,
    val previousLevel: Int? = null,
    val currentLevel: Int? = null,
    val eventId: String? = null,
    val taskKey: String? = null,
    val resinCost: Int? = null,
)

@Service
class SnapshotActivityService(
    properties: GenshinContentProperties,
    private val objectMapper: ObjectMapper,
    private val domainScheduleService: DomainScheduleService = DomainScheduleService(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val playerDataDirectory = Path.of(properties.cacheDirectory)
        .toAbsolutePath()
        .normalize()
        .resolve("player-data")
    private val locks = ConcurrentHashMap<Long, Any>()
    private val detector = SnapshotActivityDetector()

    fun record(
        userId: Long,
        previous: PlayerSnapshot?,
        current: PlayerSnapshot,
    ) {
        if (previous == null) return
        val detected = detector.detect(previous, current)
        recordEvents(userId, detected)
    }

    fun recordEvents(userId: Long, detected: List<SnapshotActivityEvent>) {
        if (detected.isEmpty()) return

        synchronized(lockFor(userId)) {
            runCatching {
                val previous = readHistory(userId)
                runCatching { rememberFarmingClaims(userId, detected + previous) }.onFailure {
                    logger.warn("Farming claims for user {} could not be saved", userId, it)
                }
                // Keep claims independently of the short activity feed, including legacy entries.
                runCatching { rememberWeeklyClaims(userId, detected + previous) }.onFailure {
                    logger.warn("Weekly boss claims for user {} could not be saved", userId, it)
                }
                val history = (detected + previous).take(MAX_STORED_EVENTS)
                writeAtomically(activityPath(userId), objectMapper.writeValueAsBytes(history))
            }.onFailure {
                logger.warn("Recent snapshot activity for user {} could not be saved", userId, it)
            }
        }
    }

    fun weeklyBossProgress(userId: Long, now: Instant = Instant.now()): WeeklyBossProgress =
        synchronized(lockFor(userId)) {
            val day = gameDate(now)
            val week = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val claims = runCatching { rememberWeeklyClaims(userId, readHistory(userId)) }
                .onFailure { logger.warn("Weekly boss claims for user {} could not be loaded", userId, it) }
                .getOrDefault(emptyList())
                .filter { gameDate(it.occurredAt) in week..day }
                .sortedBy { it.occurredAt }
                .map { WeeklyBossClaim(GoodKeyNormalizer.normalize(it.name), gameDate(it.occurredAt).toString()) }
            WeeklyBossProgress(day.toString(), week.toString(), claims)
        }

    private fun gameDate(instant: Instant) =
        domainScheduleService.currentGameDate(instant.atZone(ZoneOffset.UTC))

    fun dailyFarmingProgress(userId: Long, now: Instant = Instant.now()): DailyFarmingProgress =
        synchronized(lockFor(userId)) {
            val day = gameDate(now)
            val events = runCatching { rememberFarmingClaims(userId, readHistory(userId)) }
                .onFailure { logger.warn("Farming claims for user {} could not be loaded", userId, it) }
                .getOrDefault(emptyList())
                .filter { gameDate(it.occurredAt) == day }
            DailyFarmingProgress(day.toString(), events.groupBy { requireNotNull(it.taskKey) }.map { (key, claims) ->
                DailyFarmingRun(key, claims.size, claims.sumOf { it.resinCost ?: 0 }, claims.count { it.resinCost == null })
            })
        }

    private fun rememberFarmingClaims(userId: Long, events: List<SnapshotActivityEvent>): List<SnapshotActivityEvent> {
        val path = activityPath(userId).resolveSibling("farming-claims.json")
        val saved: List<SnapshotActivityEvent> = if (Files.isRegularFile(path)) Files.newInputStream(path).use {
            objectMapper.readValue(it, object : TypeReference<List<SnapshotActivityEvent>>() {})
        } else emptyList()
        val merged = (saved + events.filter { it.type in FARMING_TYPES && it.taskKey != null && it.eventId != null })
            .distinctBy { it.eventId }.sortedBy { it.occurredAt }
        val earliest = merged.lastOrNull()?.let { gameDate(it.occurredAt).minusDays(1) }
        val claims = if (earliest == null) emptyList() else merged.filter { gameDate(it.occurredAt) >= earliest }
        if (saved != claims) writeAtomically(path, objectMapper.writeValueAsBytes(claims))
        return claims
    }

    private fun rememberWeeklyClaims(userId: Long, events: List<SnapshotActivityEvent>): List<SnapshotActivityEvent> {
        val path = activityPath(userId).resolveSibling("weekly-boss-claims.json")
        val saved: List<SnapshotActivityEvent> = if (Files.isRegularFile(path)) {
            Files.newInputStream(path).use {
                objectMapper.readValue(it, object : TypeReference<List<SnapshotActivityEvent>>() {})
            }
        } else emptyList()
        val claims = (saved + events.filter { it.type == SnapshotActivityType.WEEKLY_BOSS_COLLECTED })
            .sortedBy { it.occurredAt }
            .distinctBy {
                gameDate(it.occurredAt).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to
                    GoodKeyNormalizer.normalize(it.name)
            }
            .takeLast(MAX_STORED_EVENTS)
        if (saved != claims) writeAtomically(path, objectMapper.writeValueAsBytes(claims))
        return claims
    }

    fun recent(userId: Long, limit: Int = DEFAULT_VISIBLE_EVENTS): List<SnapshotActivityEvent> {
        require(limit in 1..MAX_STORED_EVENTS) { "Invalid activity limit" }
        return synchronized(lockFor(userId)) {
            runCatching { readHistory(userId).take(limit) }
                .onFailure {
                    logger.warn("Recent snapshot activity for user {} could not be loaded", userId, it)
                }
                .getOrDefault(emptyList())
        }
    }

    private fun readHistory(userId: Long): List<SnapshotActivityEvent> {
        val path = activityPath(userId)
        if (!Files.isRegularFile(path)) return emptyList()
        return Files.newInputStream(path).use {
            objectMapper.readValue(
                it,
                object : TypeReference<List<SnapshotActivityEvent>>() {},
            )
        }.take(MAX_STORED_EVENTS)
    }

    private fun activityPath(userId: Long): Path {
        require(userId > 0) { "Invalid user id" }
        val userDirectory = playerDataDirectory.resolve(userId.toString()).normalize()
        check(userDirectory.parent == playerDataDirectory) { "Invalid player data path" }
        return userDirectory.resolve("recent-activity.json")
    }

    private fun lockFor(userId: Long): Any = locks.computeIfAbsent(userId) { Any() }

    private fun writeAtomically(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val temporaryFile = Files.createTempFile(path.parent, "recent-activity", ".tmp")
        try {
            Files.write(temporaryFile, bytes)
            try {
                Files.move(
                    temporaryFile,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    companion object {
        const val DEFAULT_VISIBLE_EVENTS = 10
        private const val MAX_STORED_EVENTS = 50
        val FARMING_TYPES = setOf(
            SnapshotActivityType.MORA_LEY_LINE, SnapshotActivityType.EXPERIENCE_LEY_LINE,
            SnapshotActivityType.TALENT_DOMAIN, SnapshotActivityType.WEAPON_DOMAIN, SnapshotActivityType.ARTIFACT_DOMAIN,
        )
    }
}

data class WeeklyBossClaim(val bossKey: String, val day: String)

data class WeeklyBossProgress(val day: String, val week: String, val claims: List<WeeklyBossClaim>)

data class DailyFarmingRun(val taskKey: String, val count: Int, val resin: Int, val unconfirmedCount: Int)

data class DailyFarmingProgress(val day: String, val runs: List<DailyFarmingRun>)

class SnapshotActivityDetector {
    fun detect(
        previous: PlayerSnapshot,
        current: PlayerSnapshot,
        occurredAt: Instant = Instant.now(),
    ): List<SnapshotActivityEvent> {
        val artifacts = artifactChanges(previous, current, occurredAt)
        val weapons = weaponChanges(previous, current, occurredAt)
        val characters = characterChanges(previous, current, occurredAt)
        val materials = materialChanges(previous, current, occurredAt)

        return buildList {
            addAll(artifacts.upgrades)
            addAll(weapons.upgrades)
            addAll(characters)
            addAll(materials.filter { it.type == SnapshotActivityType.MATERIAL_GAIN })
            addAll(artifacts.additions)
            addAll(weapons.additions)
            addAll(materials.filter { it.type == SnapshotActivityType.MATERIAL_SPEND })
            addAll(artifacts.removals)
            addAll(weapons.removals)
        }.take(MAX_EVENTS_PER_SNAPSHOT)
    }

    private fun materialChanges(
        previous: PlayerSnapshot,
        current: PlayerSnapshot,
        occurredAt: Instant,
    ): List<SnapshotActivityEvent> =
        (previous.inventory.keys + current.inventory.keys)
            .distinct()
            .mapNotNull { key ->
                val oldAmount = previous.inventory[key] ?: 0
                val newAmount = current.inventory[key] ?: 0
                val delta = newAmount - oldAmount
                if (delta == 0L) return@mapNotNull null
                SnapshotActivityEvent(
                    type = if (delta > 0) {
                        SnapshotActivityType.MATERIAL_GAIN
                    } else {
                        SnapshotActivityType.MATERIAL_SPEND
                    },
                    occurredAt = occurredAt,
                    name = current.inventoryNames[key]
                        ?: previous.inventoryNames[key]
                        ?: GoodKeyNormalizer.humanize(key),
                    materialKey = key,
                    amount = kotlin.math.abs(delta),
                    total = newAmount,
                )
            }
            .sortedByDescending { it.amount }

    private fun artifactChanges(
        previous: PlayerSnapshot,
        current: PlayerSnapshot,
        occurredAt: Instant,
    ): ActivityGroups {
        val oldArtifacts = previous.artifacts.toMutableList()
        val newArtifacts = current.artifacts.toMutableList()
        removeMatches(oldArtifacts, newArtifacts) { old, new -> old == new }
        removeMatches(oldArtifacts, newArtifacts, ::sameArtifactContents)

        val upgrades = mutableListOf<SnapshotActivityEvent>()
        var newIndex = 0
        while (newIndex < newArtifacts.size) {
            val upgraded = newArtifacts[newIndex]
            val candidate = oldArtifacts.withIndex()
                .filter { (_, old) ->
                    sameArtifactBase(old, upgraded) &&
                        old.level < upgraded.level &&
                        old.substats.map { it.key }.all { oldKey ->
                            upgraded.substats.any { it.key == oldKey }
                        }
                }
                .maxByOrNull { (_, old) ->
                    old.substats.count { oldStat ->
                        upgraded.substats.any { it.key == oldStat.key }
                    } * 100 + old.level
                }
            if (candidate == null) {
                newIndex++
                continue
            }

            oldArtifacts.removeAt(candidate.index)
            newArtifacts.removeAt(newIndex)
            upgrades += SnapshotActivityEvent(
                type = SnapshotActivityType.ARTIFACT_LEVEL,
                occurredAt = occurredAt,
                name = upgraded.setName,
                detailName = upgraded.slotName,
                previousLevel = candidate.value.level,
                currentLevel = upgraded.level,
            )
        }

        val additions = newArtifacts.map { artifact ->
            SnapshotActivityEvent(
                type = SnapshotActivityType.ARTIFACT_ADDED,
                occurredAt = occurredAt,
                name = artifact.setName,
                detailName = artifact.slotName,
                currentLevel = artifact.level,
            )
        }
        val removals = if (oldArtifacts.isEmpty()) {
            emptyList()
        } else {
            listOf(
                SnapshotActivityEvent(
                    type = SnapshotActivityType.ARTIFACTS_REMOVED,
                    occurredAt = occurredAt,
                    amount = oldArtifacts.size.toLong(),
                    total = current.artifacts.size.toLong(),
                ),
            )
        }
        return ActivityGroups(upgrades, additions, removals)
    }

    private fun weaponChanges(
        previous: PlayerSnapshot,
        current: PlayerSnapshot,
        occurredAt: Instant,
    ): ActivityGroups {
        val oldWeapons = previous.weapons.toMutableList()
        val newWeapons = current.weapons.toMutableList()
        removeMatches(oldWeapons, newWeapons) { old, new -> old == new }
        removeMatches(oldWeapons, newWeapons, ::sameWeaponContents)

        val upgrades = mutableListOf<SnapshotActivityEvent>()
        var newIndex = 0
        while (newIndex < newWeapons.size) {
            val upgraded = newWeapons[newIndex]
            val candidate = oldWeapons.withIndex()
                .filter { (_, old) ->
                    GoodKeyNormalizer.normalize(old.key) ==
                        GoodKeyNormalizer.normalize(upgraded.key) &&
                        old.level < upgraded.level
                }
                .maxByOrNull { it.value.level }
            if (candidate == null) {
                newIndex++
                continue
            }
            oldWeapons.removeAt(candidate.index)
            newWeapons.removeAt(newIndex)
            upgrades += SnapshotActivityEvent(
                type = SnapshotActivityType.WEAPON_LEVEL,
                occurredAt = occurredAt,
                name = upgraded.name,
                previousLevel = candidate.value.level,
                currentLevel = upgraded.level,
            )
        }

        return ActivityGroups(
            upgrades = upgrades,
            additions = newWeapons.map { weapon ->
                SnapshotActivityEvent(
                    type = SnapshotActivityType.WEAPON_ADDED,
                    occurredAt = occurredAt,
                    name = weapon.name,
                    currentLevel = weapon.level,
                )
            },
            removals = oldWeapons.map { weapon ->
                SnapshotActivityEvent(
                    type = SnapshotActivityType.WEAPON_REMOVED,
                    occurredAt = occurredAt,
                    name = weapon.name,
                    previousLevel = weapon.level,
                )
            },
        )
    }

    private fun characterChanges(
        previous: PlayerSnapshot,
        current: PlayerSnapshot,
        occurredAt: Instant,
    ): List<SnapshotActivityEvent> {
        val previousByKey = previous.characters.associateBy {
            GoodKeyNormalizer.normalize(it.key)
        }
        return current.characters.mapNotNull { character ->
            val old = previousByKey[GoodKeyNormalizer.normalize(character.key)]
                ?: return@mapNotNull null
            if (character.level <= old.level) return@mapNotNull null
            SnapshotActivityEvent(
                type = SnapshotActivityType.CHARACTER_LEVEL,
                occurredAt = occurredAt,
                name = GoodKeyNormalizer.humanize(character.key),
                previousLevel = old.level,
                currentLevel = character.level,
            )
        }
    }

    private fun sameArtifactBase(old: PlayerArtifact, new: PlayerArtifact): Boolean =
        GoodKeyNormalizer.normalize(old.setKey) == GoodKeyNormalizer.normalize(new.setKey) &&
            old.slotKey.equals(new.slotKey, ignoreCase = true) &&
            old.rarity == new.rarity &&
            old.mainStatKey == new.mainStatKey

    private fun sameArtifactContents(old: PlayerArtifact, new: PlayerArtifact): Boolean =
        sameArtifactBase(old, new) &&
            old.level == new.level &&
            old.substats == new.substats &&
            old.totalRolls == new.totalRolls &&
            old.astralMark == new.astralMark &&
            old.elixirCrafted == new.elixirCrafted

    private fun sameWeaponContents(old: PlayerWeapon, new: PlayerWeapon): Boolean =
        GoodKeyNormalizer.normalize(old.key) == GoodKeyNormalizer.normalize(new.key) &&
            old.level == new.level &&
            old.ascension == new.ascension &&
            old.refinement == new.refinement

    private fun <T> removeMatches(
        oldItems: MutableList<T>,
        newItems: MutableList<T>,
        matches: (T, T) -> Boolean,
    ) {
        var newIndex = 0
        while (newIndex < newItems.size) {
            val oldIndex = oldItems.indexOfFirst { matches(it, newItems[newIndex]) }
            if (oldIndex < 0) {
                newIndex++
            } else {
                oldItems.removeAt(oldIndex)
                newItems.removeAt(newIndex)
            }
        }
    }

    private data class ActivityGroups(
        val upgrades: List<SnapshotActivityEvent>,
        val additions: List<SnapshotActivityEvent>,
        val removals: List<SnapshotActivityEvent>,
    )

    companion object {
        private const val MAX_EVENTS_PER_SNAPSHOT = 10
    }
}
