package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.AggregatedArtifactStat
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerCharacterEquipment
import de.tyro.genshinapp.model.PlayerCharacterState
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.TravelerIdentity
import org.springframework.stereotype.Service

@Service
class PlayerEquipmentService {
    fun equipmentFor(
        snapshot: PlayerSnapshot,
        state: PlayerCharacterState,
    ): PlayerCharacterEquipment {
        val characterKey = TravelerIdentity.canonicalCharacterKey(state.key)
        val artifacts = snapshot.artifacts
            .filter {
                TravelerIdentity.canonicalCharacterKey(it.location.orEmpty()) == characterKey
            }
            .sortedBy { SLOT_ORDER[it.slotKey.lowercase()] ?: Int.MAX_VALUE }
        val weapon = snapshot.weapons.firstOrNull {
            TravelerIdentity.canonicalCharacterKey(it.location.orEmpty()) == characterKey
        }

        return PlayerCharacterEquipment(
            weapon = weapon,
            artifacts = artifacts,
            artifactStats = aggregateStats(artifacts),
        )
    }

    private fun aggregateStats(artifacts: List<PlayerArtifact>): List<AggregatedArtifactStat> =
        artifacts
            .flatMap(PlayerArtifact::substats)
            .groupBy { it.key }
            .map { (key, stats) ->
                AggregatedArtifactStat(
                    key = key,
                    name = GoodKeyNormalizer.statName(key),
                    value = stats.sumOf { it.value },
                )
            }
            .sortedWith(
                compareBy<AggregatedArtifactStat> { STAT_ORDER[it.key] ?: Int.MAX_VALUE }
                    .thenBy { it.name },
            )

    companion object {
        private val SLOT_ORDER = mapOf(
            "flower" to 0,
            "plume" to 1,
            "sands" to 2,
            "goblet" to 3,
            "circlet" to 4,
        )
        private val STAT_ORDER = mapOf(
            "critRate_" to 0,
            "critDMG_" to 1,
            "enerRech_" to 2,
            "eleMas" to 3,
            "atk_" to 4,
            "atk" to 5,
            "hp_" to 6,
            "hp" to 7,
            "def_" to 8,
            "def" to 9,
        )
    }
}
