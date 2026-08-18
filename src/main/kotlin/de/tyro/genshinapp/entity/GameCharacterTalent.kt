package de.tyro.genshinapp.entity

import de.tyro.genshinapp.model.CharacterTalentKind
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Lob
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.BatchSize

@Entity
@Table(
    name = "game_character_talent",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_character_talent_key",
            columnNames = ["character_id", "talent_key"],
        ),
    ],
)
class GameCharacterTalent {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "character_id", nullable = false)
    lateinit var character: GameCharacter

    @Column(name = "talent_key", nullable = false, length = 64)
    var key: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "talent_kind", nullable = false, length = 32)
    var kind: CharacterTalentKind = CharacterTalentKind.PASSIVE

    @Column(nullable = false, length = 240)
    var name: String = ""

    @Lob
    @Column(nullable = false)
    var description: String = ""

    @Lob
    @Column(name = "flavor_text")
    var flavorText: String? = null

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0

    @OneToMany(mappedBy = "talent", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @BatchSize(size = 100)
    var attributes: MutableList<GameCharacterTalentAttribute> = mutableListOf()
}

@Entity
@Table(
    name = "game_character_talent_attribute",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_talent_attribute_order",
            columnNames = ["talent_id", "display_order"],
        ),
    ],
)
class GameCharacterTalentAttribute {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "talent_id", nullable = false)
    lateinit var talent: GameCharacterTalent

    @Lob
    @Column(nullable = false)
    var label: String = ""

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0

    @OneToMany(mappedBy = "attribute", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @BatchSize(size = 100)
    var values: MutableList<GameCharacterTalentAttributeValue> = mutableListOf()
}

@Entity
@Table(
    name = "game_character_talent_attribute_value",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_talent_attribute_value_order",
            columnNames = ["attribute_id", "display_order"],
        ),
    ],
)
class GameCharacterTalentAttributeValue {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(nullable = false)
    var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_id", nullable = false)
    lateinit var attribute: GameCharacterTalentAttribute

    @Lob
    @Column(name = "attribute_value", nullable = false)
    var value: String = ""

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0
}
