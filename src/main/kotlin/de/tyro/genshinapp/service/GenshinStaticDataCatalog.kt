package de.tyro.genshinapp.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import de.tyro.genshinapp.entity.GenshinStaticData
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.repository.GenshinStaticDataRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant

@Service
class GenshinStaticDataCatalog(
    private val objectMapper: ObjectMapper,
    private val repository: GenshinStaticDataRepository,
) {
    @Transactional
    fun synchronize(folder: String, sourceItems: List<JsonNode>): StaticDataSyncResult {
        val normalizedFolder = normalizeFolder(folder)
        require(sourceItems.isNotEmpty() && sourceItems.all(JsonNode::isObject)) {
            "Static data folder '$normalizedFolder' must contain at least one object"
        }
        val incoming = toIncomingItems(sourceItems)
        val stored = repository.findAllByFolderOrderByNameAsc(normalizedFolder)
            .associateBy(GenshinStaticData::catalogKey)
        val now = Instant.now()
        val changed = mutableListOf<GenshinStaticData>()
        var createdCount = 0
        var updatedCount = 0
        var unchangedCount = 0

        incoming.forEach { item ->
            val entity = stored[item.key]
            when {
                entity == null -> {
                    changed += GenshinStaticData().also {
                        it.folder = normalizedFolder
                        it.catalogKey = item.key
                        it.apply(item, now)
                    }
                    createdCount++
                }
                entity.contentHash != item.contentHash -> {
                    entity.apply(item, now)
                    changed += entity
                    updatedCount++
                }
                else -> unchangedCount++
            }
        }

        val incomingKeys = incoming.mapTo(hashSetOf(), IncomingStaticData::key)
        val removed = stored.values.filter { it.catalogKey !in incomingKeys }
        if (changed.isNotEmpty()) repository.saveAll(changed)
        if (removed.isNotEmpty()) repository.deleteAll(removed)

        return StaticDataSyncResult(
            folder = normalizedFolder,
            sourceCount = incoming.size,
            createdCount = createdCount,
            updatedCount = updatedCount,
            unchangedCount = unchangedCount,
            removedCount = removed.size,
        )
    }

    @Transactional(readOnly = true)
    fun readFolder(folder: String): List<JsonNode> =
        repository.findAllByFolderOrderByNameAsc(normalizeFolder(folder)).mapNotNull { entity ->
            runCatching { objectMapper.readTree(entity.sourceJson) }.getOrNull()
        }

    private fun toIncomingItems(sourceItems: List<JsonNode>): List<IncomingStaticData> {
        val candidates = sourceItems.map { node ->
            val canonicalJson = objectMapper.writeValueAsString(canonicalize(node))
            val name = node.path("name").asText().trim()
            require(name.isNotBlank()) { "genshin-db item is missing its name" }
            IncomingCandidate(
                node = node,
                name = name,
                baseKey = GoodKeyNormalizer.normalize(name).ifBlank {
                    "item-${sha256(canonicalJson).take(16)}"
                },
                canonicalJson = canonicalJson,
                contentHash = sha256(canonicalJson),
            )
        }
        val duplicateKeys = candidates.groupingBy(IncomingCandidate::baseKey)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        val usedKeys = hashSetOf<String>()

        return candidates.map { candidate ->
            var key = candidate.baseKey
            if (key in duplicateKeys) {
                val stableSuffix = candidate.node.path("id").asText().takeIf(String::isNotBlank)
                    ?: candidate.node.path("sortorder").asText().takeIf(String::isNotBlank)
                    ?: candidate.contentHash.take(12)
                key = "${candidate.baseKey}-$stableSuffix"
            }
            key = key.take(MAX_CATALOG_KEY_LENGTH)
            require(usedKeys.add(key)) {
                "genshin-db contains duplicate static data key '$key'"
            }
            IncomingStaticData(
                key = key,
                name = candidate.name.take(MAX_NAME_LENGTH),
                sourceVersion = candidate.node.path("version").asText()
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.take(MAX_VERSION_LENGTH),
                sourceJson = candidate.canonicalJson,
                contentHash = candidate.contentHash,
            )
        }
    }

    private fun canonicalize(node: JsonNode): JsonNode = when {
        node.isObject -> JsonNodeFactory.instance.objectNode().also { result ->
            node.properties().toList().sortedBy(Map.Entry<String, JsonNode>::key)
                .forEach { (key, value) -> result.set<JsonNode>(key, canonicalize(value)) }
        }
        node.isArray -> JsonNodeFactory.instance.arrayNode().also { result ->
            node.forEach { value -> result.add(canonicalize(value)) }
        }
        else -> node.deepCopy<JsonNode>()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun normalizeFolder(folder: String): String = folder.trim().lowercase().also {
        require(it.matches(FOLDER_PATTERN)) { "Invalid static data folder '$folder'" }
    }

    private fun GenshinStaticData.apply(item: IncomingStaticData, timestamp: Instant) {
        name = item.name
        sourceVersion = item.sourceVersion
        sourceJson = item.sourceJson
        contentHash = item.contentHash
        updatedAt = timestamp
    }

    private data class IncomingCandidate(
        val node: JsonNode,
        val name: String,
        val baseKey: String,
        val canonicalJson: String,
        val contentHash: String,
    )

    private data class IncomingStaticData(
        val key: String,
        val name: String,
        val sourceVersion: String?,
        val sourceJson: String,
        val contentHash: String,
    )

    companion object {
        private const val MAX_CATALOG_KEY_LENGTH = 192
        private const val MAX_NAME_LENGTH = 255
        private const val MAX_VERSION_LENGTH = 32
        private val FOLDER_PATTERN = Regex("[a-z0-9]+")
    }
}

data class StaticDataSyncResult(
    val folder: String,
    val sourceCount: Int,
    val createdCount: Int,
    val updatedCount: Int,
    val unchangedCount: Int,
    val removedCount: Int,
) {
    val changedCount: Int
        get() = createdCount + updatedCount + removedCount
}
