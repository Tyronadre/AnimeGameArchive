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
) {
    val iconImageUrl: String?
        get() = imageUrls[CharacterImageType.ICON]

    val cardImageUrl: String?
        get() = imageUrls[CharacterImageType.CARD]

    val wishImageUrl: String?
        get() = imageUrls[CharacterImageType.WISH]

    fun remoteImageUrl(type: CharacterImageType): String? = remoteImageUrls[type]
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
