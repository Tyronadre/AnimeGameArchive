package de.tyro.genshinapp.model

data class MaterialDefinition(
    val id: Int,
    val name: String,
    val category: MaterialCategory = MaterialCategory.OTHER,
    val craftingFamily: String? = null,
    val craftingTier: Int? = null,
    val conversionGroup: String? = null,
)

enum class MaterialCategory(val messageKey: String) {
    GEM("material.category.gem"),
    TALENT_BOOK("material.category.talentBook"),
    WEAPON_ASCENSION("material.category.weaponAscension"),
    ENEMY_DROP("material.category.enemyDrop"),
    COLLECTABLE("material.category.collectable"),
    WEEKLY_BOSS("material.category.weeklyBoss"),
    WORLD_BOSS("material.category.worldBoss"),
    OTHER("material.category.other"),
}

data class MaterialCraftingInfo(
    val material: MaterialDefinition,
    val category: MaterialCategory,
    val familyKey: String? = null,
    val tier: Int? = null,
    val conversionGroup: String? = null,
)

data class MaterialInventoryAvailability(
    val owned: Long,
    val craftable: Long,
) {
    val available: Long
        get() = owned + craftable
}
