package de.tyro.genshinapp.model

data class PlayerMaterialPlan(
    val snapshot: PlayerSnapshot,
    val characters: List<PlayerCharacterPlan>,
    val aggregateMaterials: List<InventoryMaterialBalance>,
    val unmatchedCharacterKeys: List<String>,
) {
    val matchedCharacters: Int
        get() = characters.size

    val missingMaterialKinds: Int
        get() = aggregateMaterials.count { it.missing > 0 }

    val missingMaterials: List<InventoryMaterialBalance>
        get() = aggregateMaterials.filter { it.missing > 0 }

    fun characterNeeds(materialId: Int): List<PlayerCharacterMaterialNeed> =
        characters.mapNotNull { characterPlan ->
            val material = characterPlan.materials.find { it.id == materialId }
                ?: return@mapNotNull null
            if (material.required <= 0) return@mapNotNull null

            PlayerCharacterMaterialNeed(
                character = characterPlan.character,
                state = characterPlan.state,
                required = material.required,
            )
        }.sortedWith(
            compareByDescending<PlayerCharacterMaterialNeed> { it.required }
                .thenBy { it.character.name },
        )
}

data class PlayerCharacterPlan(
    val character: CharacterDefinition,
    val state: PlayerCharacterState,
    val materials: List<InventoryMaterialBalance>,
) {
    val missingMaterials: List<InventoryMaterialBalance>
        get() = materials.filter { it.missing > 0 }

    val completed: Boolean
        get() = missingMaterials.isEmpty()
}

data class InventoryMaterialBalance(
    val id: Int,
    val name: String,
    val required: Long,
    val owned: Long,
    val missing: Long,
    val imageUrl: String?,
    val craftable: Long = 0,
    val category: MaterialCategory = MaterialCategory.OTHER,
) {
    val available: Long
        get() = owned + craftable
}

data class PlayerCharacterMaterialNeed(
    val character: CharacterDefinition,
    val state: PlayerCharacterState,
    val required: Long,
)
