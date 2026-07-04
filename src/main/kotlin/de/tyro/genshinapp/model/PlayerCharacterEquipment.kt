package de.tyro.genshinapp.model

import java.math.BigDecimal
import java.math.RoundingMode

data class PlayerCharacterEquipment(
    val weapon: PlayerWeapon?,
    val artifacts: List<PlayerArtifact>,
    val artifactStats: List<AggregatedArtifactStat>,
) {
    val artifactCount: Int
        get() = artifacts.size
}

data class AggregatedArtifactStat(
    val key: String,
    val name: String,
    val value: Double,
) {
    val formattedValue: String
        get() {
            val rounded = BigDecimal.valueOf(value)
                .setScale(1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
            return if (key.endsWith("_")) "$rounded %" else rounded
        }
}
