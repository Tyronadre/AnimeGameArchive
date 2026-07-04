package de.tyro.genshinapp.entity

import de.tyro.genshinapp.util.*
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id

@Entity
class GameCharacterStats {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    var characterLevel: Int = 1
        set(value) {
            if (CharacterUtil.allowedCharacterLevel(value)) field = value
        }

    var characterTargetLevel: Int = 1
        set(value) {
            if (CharacterUtil.allowedCharacterLevel(value)) field = value
        }

    var normalLevel: Int = 1
        set(value) {
            if (CharacterUtil.allowedTalentLevel(value)) field = value
        }

    var normalTargetLevel: Int = 1
        set(value) {
            if (CharacterUtil.allowedTalentLevel(value)) field = value
        }

    var skillLevel: Int = 1
        set(value) {
            if (CharacterUtil.allowedTalentLevel(value)) field = value
        }

    var skillTargetLevel: Int = 1
        set(value) {
            if (CharacterUtil.allowedTalentLevel(value)) field = value
        }

    var burstLevel: Int = 1
        set(value) {
            if (CharacterUtil.allowedTalentLevel(value)) field = value
        }

    var burstTargetLevel: Int = 1
        set(value) {
            if (CharacterUtil.allowedTalentLevel(value)) field = value
        }

    var ascensionLevel: Int = 1
        set(value) {
            if (CharacterUtil.allowedAscensionLevel(this, value)) field = value
        }

    var ascensionTargetLevel: Int = 1
        set(value) {
            if (CharacterUtil.allowedAscensionLevel(this, value)) field = value
        }

    var owned: Boolean = false


}