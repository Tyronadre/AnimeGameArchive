package de.tyro.genshinapp.service.damage.v2

data class DamageArtifactSetV2(
    val schemaVersion: Int,
    val key: String,
    val name: String,
    val inputs: List<DamageFormulaInputV2> = emptyList(),
    val effects: List<DamageArtifactEffectV2>,
    val damageTypes: List<DamageTypeDefinitionV2> = emptyList(),
    val sources: List<String> = emptyList(),
)

data class DamageArtifactEffectV2(
    val pieces: Int,
    val recipient: String = "wearer",
    val effect: DamageEffectV2,
    val formulas: List<DamageFormulaV2> = emptyList(),
)

data class DamageArtifactOriginV2(
    val characterKey: String,
    val setKey: String,
    val setName: String,
    val equippedPieces: Int,
    val requiredPieces: Int,
)

data class DamageArtifactLoadoutV2(
    val characterKey: String,
    val characterName: String,
    val equippedPieces: Int,
    val sets: List<DamageEquippedArtifactSetV2>,
)

data class DamageEquippedArtifactSetV2(val key: String, val name: String, val pieces: Int, val supported: Boolean)
