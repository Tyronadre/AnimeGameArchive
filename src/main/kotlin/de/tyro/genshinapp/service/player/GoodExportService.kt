package de.tyro.genshinapp.service.player

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.TravelerIdentity
import de.tyro.genshinapp.service.catalog.ArtifactCatalogService
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import de.tyro.genshinapp.service.catalog.WeaponCatalogService
import de.tyro.genshinapp.service.planning.CharacterTargetService
import org.springframework.stereotype.Service

@Service
class GoodExportService(
    private val objectMapper: ObjectMapper,
    private val snapshotStore: PlayerSnapshotStore,
    private val characterTargetService: CharacterTargetService,
    private val travelerService: TravelerService,
    private val characterCatalogService: CharacterCatalogService,
    private val artifactCatalogService: ArtifactCatalogService,
    private val weaponCatalogService: WeaponCatalogService,
    private val materialCatalogService: MaterialCatalogService,
) {
    fun export(userId: Long): ByteArray {
        val snapshot = snapshotStore.current(userId)
            ?: throw IllegalStateException("No GOOD file imported")
        val characters = exportedCharacters(userId, snapshot)
        val materialNames = materialCatalogService.getMaterials()
            .filter { it.id > 0 }
            .associate { GoodKeyNormalizer.normalize(it.name) to it.name }

        val document = linkedMapOf<String, Any>(
            "format" to GOOD_FORMAT,
            "version" to GOOD_VERSION,
            "source" to EXPORT_SOURCE,
            "characters" to characters.map(::characterDocument),
            "artifacts" to snapshot.artifacts.map(::artifactDocument),
            "weapons" to snapshot.weapons.map(::weaponDocument),
            "materials" to snapshot.inventory.toSortedMap().mapKeys { (key) ->
                snapshot.inventoryNames[key]
                    ?: materialNames[key]?.let(::goodKey)
                    ?: key
            },
        )
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document)
    }

    private fun exportedCharacters(
        userId: Long,
        snapshot: PlayerSnapshot,
    ): List<PlayerCharacterState> {
        val characters = linkedMapOf<String, PlayerCharacterState>()
        snapshot.characters.forEach { state ->
            characters.putIfAbsent(TravelerIdentity.canonicalCharacterKey(state.key), state)
        }

        characterTargetService.findAll(userId).toSortedMap().forEach { (key, target) ->
            if (target.owned == false) {
                characters.remove(key)
                return@forEach
            }
            val imported = characters[key]
            if (imported == null && target.owned != true) return@forEach
            val base = imported ?: PlayerCharacterState(
                key = characterGoodKey(key),
                level = 1,
                constellation = 0,
                ascension = 0,
                normalTalent = 1,
                skillTalent = 1,
                burstTalent = 1,
            )
            characters[key] = base.copy(
                level = target.currentLevel ?: base.level,
                constellation = target.currentConstellation ?: base.constellation,
                ascension = target.currentAscension ?: base.ascension,
                normalTalent = target.currentNormalTalent ?: base.normalTalent,
                skillTalent = target.currentSkillTalent ?: base.skillTalent,
                burstTalent = target.currentBurstTalent ?: base.burstTalent,
            )
        }

        characters[TravelerIdentity.KEY]?.let { traveler ->
            val selection = travelerService.selection(userId)
            val progress = travelerService.progress(userId, selection.element)
            if (progress != null) {
                characters[TravelerIdentity.KEY] = traveler.copy(
                    constellation = progress.constellation ?: traveler.constellation,
                    normalTalent = progress.normalTalent ?: traveler.normalTalent,
                    skillTalent = progress.skillTalent ?: traveler.skillTalent,
                    burstTalent = progress.burstTalent ?: traveler.burstTalent,
                )
            }
        }
        return characters.values.toList()
    }

    private fun characterDocument(character: PlayerCharacterState): Map<String, Any> =
        linkedMapOf(
            "key" to if (TravelerIdentity.isTraveler(character.key)) {
                TRAVELER_GOOD_KEY
            } else {
                characterGoodKey(character.key)
            },
            "level" to character.level,
            "constellation" to character.constellation,
            "ascension" to character.ascension,
            "talent" to linkedMapOf(
                "auto" to character.normalTalent,
                "skill" to character.skillTalent,
                "burst" to character.burstTalent,
            ),
        )

    private fun artifactDocument(artifact: de.tyro.genshinapp.model.PlayerArtifact): Map<String, Any> =
        linkedMapOf<String, Any>(
            "setKey" to if (
                artifact.setKey != GoodKeyNormalizer.normalize(artifact.setKey)
            ) {
                artifact.setKey
            } else {
                artifactCatalogService.setName(artifact.setKey)?.let(::goodKey) ?: artifact.setKey
            },
            "slotKey" to artifact.slotKey,
            "level" to artifact.level,
            "rarity" to artifact.rarity,
            "mainStatKey" to artifact.mainStatKey,
            "location" to goodLocation(artifact.location),
            "lock" to artifact.locked,
            "substats" to artifact.substats.map(::artifactStatDocument),
        ).apply {
            artifact.totalRolls?.let { put("totalRolls", it) }
            put("astralMark", artifact.astralMark)
            put("elixerCrafted", artifact.elixirCrafted)
            put(
                "unactivatedSubstats",
                artifact.unactivatedSubstats.map(::artifactStatDocument),
            )
        }

    private fun artifactStatDocument(
        stat: de.tyro.genshinapp.model.PlayerArtifactStat,
    ): Map<String, Any> = linkedMapOf<String, Any>(
        "key" to stat.key,
        "value" to stat.value,
    ).apply {
        stat.initialValue?.let { put("initialValue", it) }
    }

    private fun weaponDocument(weapon: de.tyro.genshinapp.model.PlayerWeapon): Map<String, Any> =
        linkedMapOf(
            "key" to if (weapon.key != GoodKeyNormalizer.normalize(weapon.key)) {
                weapon.key
            } else {
                weaponCatalogService.find(weapon.key)?.name?.let(::goodKey) ?: weapon.key
            },
            "level" to weapon.level,
            "ascension" to weapon.ascension,
            "refinement" to weapon.refinement,
            "location" to goodLocation(weapon.location),
            "lock" to weapon.locked,
        )

    private fun goodLocation(location: String?): String {
        if (location.isNullOrBlank()) return ""
        return if (TravelerIdentity.isTraveler(location)) {
            TRAVELER_GOOD_KEY
        } else {
            characterGoodKey(location)
        }
    }

    private fun characterGoodKey(key: String): String {
        if (TravelerIdentity.isTraveler(key)) return TRAVELER_GOOD_KEY
        return characterCatalogService.findCharacter(key)?.name?.let(::goodKey) ?: key
    }

    private fun goodKey(name: String): String = buildString {
        var capitalizeNext = true
        name.forEach { character ->
            if (character.isLetterOrDigit()) {
                append(if (capitalizeNext) character.uppercaseChar() else character)
                capitalizeNext = false
            } else {
                capitalizeNext = true
            }
        }
    }

    companion object {
        const val FILE_NAME = "genshin-archive-good-v3.json"
        private const val GOOD_FORMAT = "GOOD"
        private const val GOOD_VERSION = 3
        private const val EXPORT_SOURCE = "Genshin Archive"
        private const val TRAVELER_GOOD_KEY = "Traveler"
    }
}
