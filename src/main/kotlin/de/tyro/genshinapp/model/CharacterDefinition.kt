package de.tyro.genshinapp.model

data class CharacterDefinition(
    val key: String,
    val id: Long,
    val name: String,
    val title: String?,
    val description: String?,
    val weapon: String?,
    val rarity: Int,
    val birthday: String?,
    val element: String?,
    val affiliation: String?,
    val region: String?,
    val constellation: String?,
    val ascensionStatType: String?,
    val imageUrls: Map<CharacterImageType, String>,
    val remoteImageUrls: Map<CharacterImageType, String>,
    val ascensionCosts: Map<Int, List<MaterialCost>>,
    val talentCosts: Map<Int, List<MaterialCost>>,
    val talents: List<CharacterTalent> = emptyList(),
) {
    val iconImageUrl: String?
        get() = imageUrls[CharacterImageType.ICON]

    val cardImageUrl: String?
        get() = imageUrls[CharacterImageType.CARD]

    val wishImageUrl: String?
        get() = imageUrls[CharacterImageType.WISH]

    fun remoteImageUrl(type: CharacterImageType): String? = remoteImageUrls[type]

    val combatTalents: List<CharacterTalent>
        get() = talents.filter { it.kind.combat }

    val passiveTalents: List<CharacterTalent>
        get() = talents.filterNot { it.kind.combat }
}

data class CharacterTalent(
    val key: String,
    val kind: CharacterTalentKind,
    val name: String,
    val description: String,
    val flavorText: String?,
    val attributes: List<CharacterTalentAttribute> = emptyList(),
)

data class CharacterTalentAttribute(
    val label: String,
    val values: List<String>,
)

enum class CharacterTalentKind(
    val messageKey: String,
    val marker: String,
    val combat: Boolean,
    val progressField: String?,
) {
    NORMAL_ATTACK("character.talent.normalAttack", "NA", true, "normalTalent"),
    ELEMENTAL_SKILL("character.talent.elementalSkill", "E", true, "skillTalent"),
    ELEMENTAL_BURST("character.talent.elementalBurst", "Q", true, "burstTalent"),
    SPECIAL_MOVEMENT("character.talent.specialMovement", "SP", true, null),
    PASSIVE("character.talent.passive", "P", false, null),
}

data class MaterialCost(
    val id: Int,
    val name: String,
    val count: Long,
)

data class MaterialRequirement(
    val id: Int,
    val name: String,
    val amount: Long,
    val imageUrl: String?,
)
