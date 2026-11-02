package de.tyro.genshinapp.service.damage.v2

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerWeapon
import de.tyro.genshinapp.model.TravelerIdentity
import org.springframework.stereotype.Service

data class DamageWeaponDefinitionV2(
    val schemaVersion: Int, val key: String, val name: String,
    val inputs: List<DamageFormulaInputV2> = emptyList(),
    val effects: List<DamageWeaponEffectV2>, val sources: List<String> = emptyList(),
)

data class DamageWeaponEffectV2(val recipient: String = "wearer", val effect: DamageEffectV2)

@Service
class DamageFormulaWeaponServiceV2(
    private val objectMapper: ObjectMapper,
    private val equipmentBinder: DamageFormulaArtifactServiceV2,
) {
    private val definitions by reloadableFormulaV2 {
        val weapons = DamageFormulaResourcesV2.resources("classpath*:data/damage-formulas-v2/weapons/*.json")
            .map { it.inputStream.use { stream -> objectMapper.readValue(stream, DamageWeaponDefinitionV2::class.java) } }
        require(weapons.map { it.key }.distinct().size == weapons.size) { "Duplicate weapon formula keys" }
        weapons.forEach {
            require(it.schemaVersion == 2 && it.key == GoodKeyNormalizer.normalize(it.key))
            require(it.effects.map { effect -> effect.effect.id }.distinct().size == it.effects.size)
            require(it.effects.all { effect -> effect.recipient in listOf("wearer", "party", "others") })
        }
        weapons.associateBy { it.key }
    }

    fun allDefinitions(): Collection<DamageWeaponDefinitionV2> = definitions.values

    fun attach(profile: DamageFormulaProfileV2, weapons: List<PlayerWeapon>): DamageFormulaProfileV2 {
        val owners = profile.selectedTeam + profile.characterKey
        val equipped = weapons.filter { TravelerIdentity.canonicalCharacterKey(it.location.orEmpty()) in owners }
            .groupBy { TravelerIdentity.canonicalCharacterKey(it.location.orEmpty()) }
        require(equipped.values.all { it.size == 1 }) { "Multiple weapons equipped on the same character" }
        val refinements = equipped.mapValues { (_, copies) -> copies.single().refinement.coerceIn(1, 5) }
        val tiers = definitions.mapValues { (_, weapon) -> DamageArtifactSetV2(
            weapon.schemaVersion, weapon.key, weapon.name, weapon.inputs,
            weapon.effects.map { DamageArtifactEffectV2(1, it.recipient, it.effect) }, sources = weapon.sources,
        ) }
        val gear = equipped.map { (owner, copies) -> EquippedEffectItemV2(owner, copies.single().key, "weapon") }
        return equipmentBinder.bindEquipment(profile, gear, tiers, "weapon", refinements)
    }
}
