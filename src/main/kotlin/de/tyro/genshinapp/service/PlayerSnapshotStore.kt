package de.tyro.genshinapp.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerArtifactStat
import de.tyro.genshinapp.model.PlayerSnapshot
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service
class PlayerSnapshotStore(
    properties: GenshinContentProperties,
    private val goodImportService: GoodImportService,
    private val objectMapper: ObjectMapper,
    private val snapshotActivityService: SnapshotActivityService? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val playerDataDirectory = Path.of(properties.cacheDirectory)
        .toAbsolutePath()
        .normalize()
        .resolve("player-data")
    private val states = ConcurrentHashMap<Long, PlayerSnapshotState>()

    fun save(userId: Long, bytes: ByteArray): PlayerSnapshot {
        val snapshot = goodImportService.parse(bytes)
        val state = stateFor(userId)
        val previous: PlayerSnapshot?
        synchronized(state.writeLock) {
            previous = state.baseSnapshot.get()
            writeAtomically(state.snapshotFile, bytes)
            writeAtomically(state.inventoryOverridesFile, "{}".toByteArray())
            Files.deleteIfExists(state.artifactOverridesFile)
            state.inventoryOverrides.set(emptyMap())
            state.artifactOverrides.set(null)
            state.baseSnapshot.set(snapshot)
            state.revision.incrementAndGet()
        }
        val saved = current(userId) ?: snapshot
        snapshotActivityService?.record(userId, previous, saved)
        return saved
    }

    fun current(userId: Long): PlayerSnapshot? {
        val state = stateFor(userId)
        val snapshot = state.baseSnapshot.get() ?: return null
        return snapshot.copy(
            revision = state.revision.get(),
            inventory = snapshot.inventory + state.inventoryOverrides.get(),
            artifacts = state.artifactOverrides.get() ?: snapshot.artifacts,
        )
    }

    fun updateInventoryAmount(userId: Long, key: String, amount: Long): PlayerSnapshot {
        require(key.matches(INVENTORY_KEY_PATTERN)) { "Invalid inventory key" }
        require(amount >= 0) { "Inventory amount must not be negative" }
        val state = stateFor(userId)
        val snapshot = state.baseSnapshot.get() ?: throw IllegalStateException("No GOOD file imported")
        val previous = current(userId) ?: throw IllegalStateException("No GOOD file imported")

        synchronized(state.writeLock) {
            val updatedOverrides = state.inventoryOverrides.get().toMutableMap()
            if (snapshot.inventory[key] == amount) {
                updatedOverrides.remove(key)
            } else {
                updatedOverrides[key] = amount
            }
            writeAtomically(
                state.inventoryOverridesFile,
                objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(updatedOverrides.toSortedMap()),
            )
            state.inventoryOverrides.set(updatedOverrides)
            state.revision.incrementAndGet()
        }
        val updated = current(userId) ?: throw IllegalStateException("No GOOD file imported")
        snapshotActivityService?.record(userId, previous, updated)
        return updated
    }

    fun updateArtifacts(
        userId: Long,
        update: (List<PlayerArtifact>) -> List<PlayerArtifact>,
    ): PlayerSnapshot {
        val state = stateFor(userId)
        val snapshot = state.baseSnapshot.get() ?: throw IllegalStateException("No GOOD file imported")
        val previous = current(userId) ?: throw IllegalStateException("No GOOD file imported")
        synchronized(state.writeLock) {
            val currentArtifacts = state.artifactOverrides.get() ?: snapshot.artifacts
            val updatedArtifacts = update(currentArtifacts)
            require(updatedArtifacts.size <= GoodImportService.MAX_ARTIFACTS) {
                "Too many artifacts"
            }
            writeAtomically(
                state.artifactOverridesFile,
                objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(updatedArtifacts.map(StoredArtifact::from)),
            )
            state.artifactOverrides.set(updatedArtifacts)
            state.revision.incrementAndGet()
        }
        val updated = current(userId) ?: throw IllegalStateException("No GOOD file imported")
        snapshotActivityService?.record(userId, previous, updated)
        return updated
    }

    fun filePath(userId: Long): Path = stateFor(userId).snapshotFile

    private fun stateFor(userId: Long): PlayerSnapshotState {
        require(userId > 0) { "Invalid user id" }
        return states.computeIfAbsent(userId, ::loadState)
    }

    private fun loadState(userId: Long): PlayerSnapshotState {
        val userDirectory = playerDataDirectory.resolve(userId.toString()).normalize()
        check(userDirectory.parent == playerDataDirectory) { "Invalid player data path" }
        val state = PlayerSnapshotState(
            snapshotFile = userDirectory.resolve("current-good.json"),
            inventoryOverridesFile = userDirectory.resolve("inventory-overrides.json"),
            artifactOverridesFile = userDirectory.resolve("artifact-overrides.json"),
        )
        loadStoredSnapshot(state)
        loadInventoryOverrides(state)
        loadArtifactOverrides(state)
        return state
    }

    private fun loadStoredSnapshot(state: PlayerSnapshotState) {
        if (!Files.isRegularFile(state.snapshotFile)) return

        runCatching {
            val importedAt = Files.getLastModifiedTime(state.snapshotFile).toInstant()
            goodImportService.parse(Files.readAllBytes(state.snapshotFile), importedAt)
        }.onSuccess(state.baseSnapshot::set)
            .onFailure {
                logger.error("Stored GOOD file {} could not be loaded", state.snapshotFile, it)
            }
    }

    private fun loadInventoryOverrides(state: PlayerSnapshotState) {
        if (!Files.isRegularFile(state.inventoryOverridesFile)) return

        runCatching {
            Files.newInputStream(state.inventoryOverridesFile).use {
                objectMapper.readValue(it, object : TypeReference<Map<String, Long>>() {})
            }.also { overrides ->
                require(overrides.all { (key, amount) ->
                    key.matches(INVENTORY_KEY_PATTERN) && amount >= 0
                })
            }
        }.onSuccess(state.inventoryOverrides::set)
            .onFailure {
                logger.error(
                    "Inventory overrides {} could not be loaded",
                    state.inventoryOverridesFile,
                    it,
                )
            }
    }

    private fun loadArtifactOverrides(state: PlayerSnapshotState) {
        if (!Files.isRegularFile(state.artifactOverridesFile)) return

        runCatching {
            Files.newInputStream(state.artifactOverridesFile).use {
                objectMapper.readValue(it, object : TypeReference<List<StoredArtifact>>() {})
                    .map(StoredArtifact::toPlayerArtifact)
            }.also { artifacts ->
                require(artifacts.size <= GoodImportService.MAX_ARTIFACTS)
            }
        }.onSuccess(state.artifactOverrides::set)
            .onFailure {
                logger.error(
                    "Artifact overrides {} could not be loaded",
                    state.artifactOverridesFile,
                    it,
                )
            }
    }

    private fun writeAtomically(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val temporaryFile = Files.createTempFile(path.parent, "player-data", ".tmp")
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

    private data class PlayerSnapshotState(
        val snapshotFile: Path,
        val inventoryOverridesFile: Path,
        val artifactOverridesFile: Path,
        val baseSnapshot: AtomicReference<PlayerSnapshot?> = AtomicReference(),
        val inventoryOverrides: AtomicReference<Map<String, Long>> = AtomicReference(emptyMap()),
        val artifactOverrides: AtomicReference<List<PlayerArtifact>?> = AtomicReference(),
        val revision: AtomicLong = AtomicLong(),
        val writeLock: Any = Any(),
    )

    private data class StoredArtifact(
        val setKey: String,
        val slotKey: String,
        val level: Int,
        val rarity: Int,
        val mainStatKey: String,
        val location: String?,
        val locked: Boolean,
        val substats: List<StoredArtifactStat>,
        val totalRolls: Int?,
        val astralMark: Boolean,
        val elixirCrafted: Boolean,
    ) {
        fun toPlayerArtifact(): PlayerArtifact = PlayerArtifact(
            setKey = setKey,
            slotKey = slotKey,
            level = level,
            rarity = rarity,
            mainStatKey = mainStatKey,
            location = location,
            locked = locked,
            substats = substats.map { PlayerArtifactStat(it.key, it.value) },
            totalRolls = totalRolls,
            astralMark = astralMark,
            elixirCrafted = elixirCrafted,
        )

        companion object {
            fun from(artifact: PlayerArtifact): StoredArtifact = StoredArtifact(
                setKey = artifact.setKey,
                slotKey = artifact.slotKey,
                level = artifact.level,
                rarity = artifact.rarity,
                mainStatKey = artifact.mainStatKey,
                location = artifact.location,
                locked = artifact.locked,
                substats = artifact.substats.map {
                    StoredArtifactStat(it.key, it.value)
                },
                totalRolls = artifact.totalRolls,
                astralMark = artifact.astralMark,
                elixirCrafted = artifact.elixirCrafted,
            )
        }
    }

    private data class StoredArtifactStat(
        val key: String,
        val value: Double,
    )

    companion object {
        private val INVENTORY_KEY_PATTERN = Regex("[a-z0-9]+")
    }
}
