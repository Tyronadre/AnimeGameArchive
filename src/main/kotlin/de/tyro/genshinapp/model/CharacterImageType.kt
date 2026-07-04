package de.tyro.genshinapp.model

enum class CharacterImageType(
    val key: String,
    val label: String,
) {
    ICON("icon", "Icon"),
    CARD("card", "Card"),
    WISH("wish", "Wish"),
    ;

    fun fileName(characterName: String): String {
        val normalizedName = characterName.trim().replace(WHITESPACE, "_")
        return when (this) {
            ICON -> "${normalizedName}_Icon.png"
            CARD -> "${normalizedName}_Card.png"
            WISH -> "Character_${normalizedName}_Full_Wish.png"
        }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun fromKey(key: String): CharacterImageType? =
            entries.find { it.key.equals(key, ignoreCase = true) }
    }
}
