package de.tyro.genshinapp.model

enum class WeaponImageType(
    val key: String,
    val label: String,
) {
    ICON("icon", "Icon"),
    FULL_ASCENDED("full-ascended", "Full image · Ascended"),
    FULL_UNASCENDED("full-unascended", "Full image · Unascended"),
    ;

    companion object {
        fun fromKey(key: String): WeaponImageType? = entries.firstOrNull { it.key == key.lowercase() }
    }
}
