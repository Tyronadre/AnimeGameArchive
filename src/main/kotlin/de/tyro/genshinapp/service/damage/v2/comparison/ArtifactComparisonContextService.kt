package de.tyro.genshinapp.service.damage.v2.comparison

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.service.damage.v2.*
import de.tyro.genshinapp.service.player.PlayerSnapshotStore
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class ArtifactComparisonContextService(
    private val mapper: ObjectMapper,
    private val snapshots: PlayerSnapshotStore,
    private val calculator: DamageFormulaServiceV2,
    private val artifacts: DamageFormulaArtifactServiceV2,
    private val weapons: DamageFormulaWeaponServiceV2,
) {
    fun snapshot(userId: Long): PlayerSnapshot? = snapshots.current(userId)

    fun fingerprint(profile: DamageFormulaProfileV2, userId: Long): String = fingerprint(profile, snapshot(userId))

    fun fingerprint(profile: DamageFormulaProfileV2, snapshot: PlayerSnapshot?): String {
        val bytes = mapper.writeValueAsBytes(listOf(profile,
            snapshot?.let { listOf(it.importedAt.toString(), it.characters, it.artifacts, it.weapons) },
            calculator.definitionContext(profile), artifacts.allDefinitions(), weapons.allDefinitions()))
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
