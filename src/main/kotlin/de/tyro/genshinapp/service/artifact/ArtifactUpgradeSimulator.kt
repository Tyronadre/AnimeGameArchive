package de.tyro.genshinapp.service.artifact

import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerArtifactStat
import java.security.MessageDigest
import kotlin.random.Random

/** Ordinary five-star upgrade generation, independent of any quality or damage objective. */
object ArtifactUpgradeSimulator {
    const val VERSION = 1
    val rollValues = mapOf(
        "hp" to listOf(209.13, 239.0, 268.88, 298.75),
        "hp_" to listOf(4.08, 4.66, 5.25, 5.83),
        "atk" to listOf(13.62, 15.56, 17.51, 19.45),
        "atk_" to listOf(4.08, 4.66, 5.25, 5.83),
        "def" to listOf(16.2, 18.52, 20.83, 23.15),
        "def_" to listOf(5.1, 5.83, 6.56, 7.29),
        "critRate_" to listOf(2.72, 3.11, 3.5, 3.89),
        "critDMG_" to listOf(5.44, 6.22, 6.99, 7.77),
        "enerRech_" to listOf(4.53, 5.18, 5.83, 6.48),
        "eleMas" to listOf(16.32, 18.65, 20.98, 23.31),
    )
    val weights = mapOf("hp" to 6.0, "atk" to 6.0, "def" to 6.0,
        "hp_" to 4.0, "atk_" to 4.0, "def_" to 4.0, "enerRech_" to 4.0,
        "eleMas" to 4.0, "critRate_" to 3.0, "critDMG_" to 3.0)

    fun remaining(artifact: PlayerArtifact): Int = (5 - artifact.level.coerceIn(0, 20) / 4).coerceAtLeast(0)

    fun finish(artifact: PlayerArtifact, random: Random, onUpgrade: (String) -> Unit = {}): PlayerArtifact {
        val stats = artifact.substats.associateTo(linkedMapOf()) { it.key to it.value }
        repeat(remaining(artifact)) {
            val key = if (stats.size < 4) {
                val options = weights.filterKeys { it != artifact.mainStatKey && it !in stats }
                var ticket = random.nextDouble() * options.values.sum()
                options.entries.firstOrNull { ticket -= it.value; ticket <= 0.0 }?.key ?: options.keys.last()
            } else stats.keys.random(random)
            stats[key] = stats.getOrDefault(key, 0.0) + rollValues.getValue(key).random(random)
            onUpgrade(key)
        }
        return artifact.copy(level = 20, substats = stats.map { PlayerArtifactStat(it.key, it.value) },
            totalRolls = artifact.totalRolls?.plus(remaining(artifact)))
    }

    fun seed(artifact: PlayerArtifact): Int {
        val content = listOf(VERSION, artifact.setKey, artifact.slotKey, artifact.rarity, artifact.level,
            artifact.mainStatKey, artifact.substats.sortedBy { it.key }.map { it.key to it.value }).toString()
        return MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).take(4)
            .fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 255) }
    }

    fun unavailableReason(artifact: PlayerArtifact): String? = when {
        artifact.rarity != 5 -> "fiveStarOnly"
        artifact.level !in 0..20 -> "invalidArtifact"
        artifact.astralMark || artifact.elixirCrafted || artifact.unactivatedSubstats.isNotEmpty() -> "specialUpgrades"
        artifact.substats.size !in 3..4 || (artifact.level >= 4 && artifact.substats.size != 4) ||
            artifact.substats.map { it.key }.distinct().size != artifact.substats.size ||
            artifact.substats.any { it.key == artifact.mainStatKey || it.key !in rollValues || !it.value.isFinite() || it.value < 0 } -> "invalidArtifact"
        else -> null
    }
}
