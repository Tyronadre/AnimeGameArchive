package de.tyro.genshinapp.model

enum class TravelerAppearance(
    val key: String,
    val characterKey: String,
    val messageKey: String,
) {
    AETHER("aether", "aether", "character.traveler.aether"),
    LUMINE("lumine", "lumine", "character.traveler.lumine"),
    ;

    val resourceKey: String
        get() = "traveler$key"

    companion object {
        fun fromKey(value: String?): TravelerAppearance? {
            val normalized = GoodKeyNormalizer.normalize(value.orEmpty())
            return entries.firstOrNull {
                normalized == it.key || normalized == it.resourceKey
            }
        }
    }
}

enum class TravelerElement(
    val key: String,
    val displayName: String,
    private val iconPath: String,
) {
    ANEMO("anemo", "Anemo", "2/28"),
    GEO("geo", "Geo", "6/68"),
    ELECTRO("electro", "Electro", "5/53"),
    DENDRO("dendro", "Dendro", "8/89"),
    HYDRO("hydro", "Hydro", "e/e3"),
    PYRO("pyro", "Pyro", "9/91"),
    ;

    val variantKey: String
        get() = "traveler$key"

    val queryName: String
        get() = "Traveler ($displayName)"

    val iconUrl: String
        get() = "https://static.wikia.nocookie.net/gensin-impact/images/" +
            "$iconPath/Traveler_Element_$displayName.png"

    companion object {
        fun fromKey(value: String?): TravelerElement? {
            val normalized = GoodKeyNormalizer.normalize(value.orEmpty())
            return entries.firstOrNull {
                normalized == it.key || normalized == it.variantKey
            }
        }
    }
}

object TravelerIdentity {
    const val KEY = "traveler"

    private val aliases = setOf(KEY, "aether", "lumine")

    fun isTraveler(value: String?): Boolean =
        GoodKeyNormalizer.normalize(value.orEmpty()) in aliases

    fun canonicalCharacterKey(value: String): String {
        val normalized = GoodKeyNormalizer.normalize(value)
        return if (normalized in aliases) KEY else normalized
    }
}

data class TravelerSelection(
    val appearance: TravelerAppearance,
    val element: TravelerElement,
    val elementConfigured: Boolean,
)

data class TravelerElementProgress(
    val constellation: Int?,
    val normalTalent: Int?,
    val skillTalent: Int?,
    val burstTalent: Int?,
    val targetNormalTalent: Int,
    val targetSkillTalent: Int,
    val targetBurstTalent: Int,
) {
    fun applyTo(form: CharacterProgressForm) {
        constellation?.let { form.constellation = it }
        normalTalent?.let { form.normalTalent = it }
        skillTalent?.let { form.skillTalent = it }
        burstTalent?.let { form.burstTalent = it }
        form.targetNormalTalent = maxOf(targetNormalTalent, form.normalTalent)
        form.targetSkillTalent = maxOf(targetSkillTalent, form.skillTalent)
        form.targetBurstTalent = maxOf(targetBurstTalent, form.burstTalent)
    }
}
