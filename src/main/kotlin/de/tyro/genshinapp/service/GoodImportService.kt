package de.tyro.genshinapp.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.configuration.LocalizedMessageArgument
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerArtifactStat
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.PlayerWeapon
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class GoodImportService(
    private val objectMapper: ObjectMapper,
) {
    fun parse(bytes: ByteArray, importedAt: Instant = Instant.now()): PlayerSnapshot {
        if (bytes.isEmpty()) throw GoodImportException("good.error.empty")
        if (bytes.size > MAX_FILE_SIZE) {
            throw GoodImportException("good.error.fileSize")
        }

        val root = runCatching { objectMapper.readTree(bytes) }
            .getOrElse { throw GoodImportException("good.error.invalidJson", cause = it) }
        if (!root.isObject || root.path("format").asText() != GOOD_FORMAT) {
            throw GoodImportException("good.error.invalidFormat")
        }

        val version = root.path("version").asInt(-1)
        if (version < MINIMUM_SUPPORTED_VERSION) {
            throw GoodImportException("good.error.unsupportedVersion")
        }

        val characterNodes = root.path("characters")
        if (!characterNodes.isArray) {
            throw GoodImportException("good.error.charactersMissing")
        }
        if (characterNodes.size() > MAX_CHARACTERS) {
            throw GoodImportException("good.error.tooManyCharacters")
        }

        val characters = characterNodes.mapIndexed { index, node ->
            parseCharacter(node, index)
        }
        val duplicateCharacter = characters
            .groupBy { GoodKeyNormalizer.normalize(it.key) }
            .entries
            .firstOrNull { it.value.size > 1 }
        if (duplicateCharacter != null) {
            throw GoodImportException(
                "good.error.duplicateCharacter",
                duplicateCharacter.value.first().key,
            )
        }

        val inventory = linkedMapOf<String, Long>()
        val inventoryNames = linkedMapOf<String, String>()
        val materialsNode = root.path("materials")
        if (!materialsNode.isMissingNode && !materialsNode.isObject) {
            throw GoodImportException("good.error.materialsType")
        }
        if (materialsNode.isObject) {
            materialsNode.properties().forEach { (exportKey, quantityNode) ->
                val quantity = quantityNode.integralValueOrNull()
                    ?: throw GoodImportException(
                        "good.error.quantityInteger",
                        exportKey,
                    )
                if (quantity < 0) {
                    throw GoodImportException("good.error.quantityNegative", exportKey)
                }
                val normalizedKey = GoodKeyNormalizer.normalize(exportKey)
                if (normalizedKey.isNotBlank()) {
                    inventory[normalizedKey] = Math.addExact(
                        inventory.getOrDefault(normalizedKey, 0L),
                        quantity,
                    )
                    inventoryNames.putIfAbsent(normalizedKey, exportKey)
                }
            }
        }

        val artifacts = parseArtifacts(root.path("artifacts"))
        val weapons = parseWeapons(root.path("weapons"))

        return PlayerSnapshot(
            formatVersion = version,
            source = root.path("source").takeIf(JsonNode::isTextual)?.asText(),
            importedAt = importedAt,
            characters = characters,
            inventory = inventory,
            inventoryNames = inventoryNames,
            exportedInventoryKeys = if (materialsNode.isObject) materialsNode.size() else 0,
            artifacts = artifacts,
            weapons = weapons,
        )
    }

    private fun parseArtifacts(node: JsonNode): List<PlayerArtifact> {
        if (node.isMissingNode || node.isNull) return emptyList()
        if (!node.isArray) throw GoodImportException("good.error.artifactsType")
        if (node.size() > MAX_ARTIFACTS) {
            throw GoodImportException("good.error.tooManyArtifacts")
        }

        return node.mapIndexed { index, artifact ->
            val label = ImportEntryLabel("good.entry.artifact", index + 1)
            val substats = artifact.path("substats")
            if (!substats.isArray) {
                throw GoodImportException(
                    "good.error.invalidSubstats",
                    label.localizedArgument,
                )
            }
            PlayerArtifact(
                setKey = artifact.requiredText("setKey", label),
                slotKey = artifact.requiredText("slotKey", label),
                level = artifact.requiredInt("level", 0..20, label),
                rarity = artifact.requiredInt("rarity", 1..5, label),
                mainStatKey = artifact.requiredText("mainStatKey", label),
                location = artifact.optionalText("location"),
                locked = artifact.path("lock").asBoolean(false),
                substats = substats.map { stat ->
                    PlayerArtifactStat(
                        key = stat.requiredText("key", label),
                        value = stat.path("value").takeIf(JsonNode::isNumber)?.asDouble()
                            ?: throw GoodImportException(
                                "good.error.invalidSubstat",
                                label.localizedArgument,
                            ),
                    )
                },
                totalRolls = artifact.path("totalRolls")
                    .takeIf(JsonNode::isIntegralNumber)
                    ?.asInt(),
                astralMark = artifact.path("astralMark").asBoolean(false),
                elixirCrafted = artifact.path("elixerCrafted").asBoolean(false),
            )
        }
    }

    private fun parseWeapons(node: JsonNode): List<PlayerWeapon> {
        if (node.isMissingNode || node.isNull) return emptyList()
        if (!node.isArray) throw GoodImportException("good.error.weaponsType")
        if (node.size() > MAX_WEAPONS) {
            throw GoodImportException("good.error.tooManyWeapons")
        }

        return node.mapIndexed { index, weapon ->
            val label = ImportEntryLabel("good.entry.weapon", index + 1)
            PlayerWeapon(
                key = weapon.requiredText("key", label),
                level = weapon.requiredInt("level", 1..90, label),
                ascension = weapon.requiredInt("ascension", 0..6, label),
                refinement = weapon.requiredInt("refinement", 1..5, label),
                location = weapon.optionalText("location"),
                locked = weapon.path("lock").asBoolean(false),
            )
        }
    }

    private fun parseCharacter(node: JsonNode, index: Int): PlayerCharacterState {
        if (!node.isObject) throw GoodImportException("good.error.invalidCharacter", index + 1)
        val key = node.path("key").takeIf(JsonNode::isTextual)?.asText()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw GoodImportException("good.error.characterKeyMissing", index + 1)
        val talent = node.path("talent")
        val label = ImportEntryLabel("good.entry.named", key)

        return PlayerCharacterState(
            key = key,
            level = node.requiredInt("level", 1..90, label),
            constellation = node.requiredInt("constellation", 0..6, label),
            ascension = node.requiredInt("ascension", 0..6, label),
            normalTalent = talent.requiredInt("auto", 1..10, label),
            skillTalent = talent.requiredInt("skill", 1..10, label),
            burstTalent = talent.requiredInt("burst", 1..10, label),
        )
    }

    private fun JsonNode.requiredInt(
        field: String,
        range: IntRange,
        label: ImportEntryLabel,
    ): Int {
        val valueNode = path(field)
        if (!valueNode.isIntegralNumber) {
            throw GoodImportException(
                "good.error.fieldInteger",
                field,
                label.localizedArgument,
            )
        }
        val value = valueNode.asInt()
        if (value !in range) {
            throw GoodImportException(
                "good.error.fieldRange",
                field,
                label.localizedArgument,
                range.first,
                range.last,
            )
        }
        return value
    }

    private fun JsonNode.requiredText(field: String, label: ImportEntryLabel): String =
        path(field).takeIf(JsonNode::isTextual)?.asText()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw GoodImportException(
                "good.error.invalidField",
                label.localizedArgument,
                field,
            )

    private fun JsonNode.optionalText(field: String): String? =
        path(field).takeIf(JsonNode::isTextual)?.asText()?.trim()?.takeIf(String::isNotBlank)

    private fun JsonNode.integralValueOrNull(): Long? =
        takeIf(JsonNode::isIntegralNumber)?.asLong()

    private data class ImportEntryLabel(
        val messageKey: String,
        val index: Any,
    ) {
        val localizedArgument: LocalizedMessageArgument
            get() = LocalizedMessageArgument(messageKey, arrayOf(index))
    }

    companion object {
        const val MAX_FILE_SIZE = 5 * 1024 * 1024
        const val MAX_ARTIFACTS = 10_000
        private const val GOOD_FORMAT = "GOOD"
        private const val MINIMUM_SUPPORTED_VERSION = 2
        private const val MAX_CHARACTERS = 500
        private const val MAX_WEAPONS = 5_000
    }
}

class GoodImportException(
    val messageKey: String,
    vararg val messageArguments: Any?,
    cause: Throwable? = null,
) : IllegalArgumentException(messageKey, cause)
