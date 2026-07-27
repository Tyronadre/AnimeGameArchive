package de.tyro.genshinapp.service

import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.MaterialSchedule
import de.tyro.genshinapp.model.MaterialSourceRole
import de.tyro.genshinapp.model.MaterialSourceType

data class MaterialSourceSeed(
    val key: String,
    val name: String,
    val type: MaterialSourceType,
    val region: String? = null,
    val displayOrder: Int = 0,
    val materials: List<MaterialSourceMaterialSeed>,
)

data class MaterialSourceMaterialSeed(
    val materialId: Int,
    val role: MaterialSourceRole,
    val familyOrder: Int = 0,
    val materialOrder: Int = 0,
    val schedule: MaterialSchedule? = null,
)

/** Bootstrap data used only to create missing database source records. */
object MaterialCatalogSeed {
    private const val PYRO = 104111
    private const val HYDRO = 104121
    private const val DENDRO = 104131
    private const val ELECTRO = 104141
    private const val ANEMO = 104151
    private const val CRYO = 104161
    private const val GEO = 104171

    val sources: List<MaterialSourceSeed> = buildList {
        addAll(
            listOf(
                TalentSeed("Forsaken Rift", "Mondstadt", listOf(104301, 104304, 104307)),
                TalentSeed("Taishan Mansion", "Liyue", listOf(104310, 104313, 104316)),
                TalentSeed("Violet Court", "Inazuma", listOf(104320, 104323, 104326)),
                TalentSeed("Steeple of Ignorance", "Sumeru", listOf(104329, 104332, 104335)),
                TalentSeed("Pale Forgotten Glory", "Fontaine", listOf(104338, 104341, 104344)),
                TalentSeed("Blazing Ruins", "Natlan", listOf(104347, 104350, 104353)),
                TalentSeed("Lightless Capital", "Nod-Krai", listOf(104356, 104359, 104362)),
            ).mapIndexed(::talentDomain),
        )
        addAll(
            listOf(
                BossSeed("Anemo Hypostasis", listOf(113001), listOf(ANEMO)),
                BossSeed("Electro Hypostasis", listOf(113002), listOf(ELECTRO)),
                BossSeed("Geo Hypostasis", listOf(113009), listOf(GEO)),
                BossSeed("Cryo Regisvine", listOf(113010), listOf(CRYO)),
                BossSeed("Pyro Regisvine", listOf(113011), listOf(PYRO)),
                BossSeed("Oceanid", listOf(113012), listOf(HYDRO)),
                BossSeed("Primo Geovishap", listOf(113016), listOf(PYRO, HYDRO, ELECTRO, CRYO, GEO)),
                BossSeed("Cryo Hypostasis", listOf(113020), listOf(CRYO)),
                BossSeed("Maguu Kenki", listOf(113022), listOf(ANEMO, CRYO)),
                BossSeed("Perpetual Mechanical Array", listOf(113023), listOf(CRYO, GEO)),
                BossSeed("Pyro Hypostasis", listOf(113024), listOf(PYRO)),
                BossSeed("Hydro Hypostasis", listOf(113028), listOf(HYDRO)),
                BossSeed("Thunder Manifestation", listOf(113029), listOf(ELECTRO)),
                BossSeed("Golden Wolflord", listOf(113030), listOf(GEO)),
                BossSeed("Bathysmal Vishap Herd", listOf(113031), listOf(ELECTRO, CRYO)),
                BossSeed("Ruin Serpent", listOf(113035), listOf(GEO)),
                BossSeed("Jadeplume Terrorshroom", listOf(113036), listOf(DENDRO)),
                BossSeed("Electro Regisvine", listOf(113037), listOf(ELECTRO)),
                BossSeed("Aeonblight Drake", listOf(113038), listOf(HYDRO, CRYO)),
                BossSeed("Algorithm of Semi-Intransient Matrix of Overseer Network", listOf(113039), listOf(PYRO, ANEMO)),
                BossSeed("Dendro Hypostasis", listOf(113040), listOf(DENDRO)),
                BossSeed("Setekh Wenut", listOf(113044), listOf(ANEMO)),
                BossSeed("Iniquitous Baptist", listOf(113045), listOf(PYRO, HYDRO, ELECTRO, CRYO)),
                BossSeed("Icewind Suite", listOf(113049, 113050), listOf(ANEMO, CRYO)),
                BossSeed("Emperor of Fire and Iron", listOf(113051), listOf(PYRO)),
                BossSeed("Experimental Field Generator", listOf(113052), listOf(GEO)),
                BossSeed("Millennial Pearl Seahorse", listOf(113053), listOf(ELECTRO)),
                BossSeed("Hydro Tulpa", listOf(113057), listOf(HYDRO)),
                BossSeed("Solitary Suanni", listOf(113058), listOf(HYDRO, ANEMO)),
                BossSeed("Legatus Golem", listOf(113059), listOf(PYRO, GEO)),
                BossSeed("Goldflame Qucusaur Tyrant", listOf(113064), listOf(PYRO)),
                BossSeed("Gluttonous Yumkasaur Mountain King", listOf(113065), listOf(DENDRO)),
                BossSeed("Secret Source Automaton: Configuration Device", listOf(113066), listOf(ELECTRO)),
                BossSeed("Tenebrous Papilla", listOf(113067), listOf(PYRO, ELECTRO, ANEMO)),
                BossSeed("Wayward Hermetic Spiritspeaker", listOf(113071), listOf(CRYO)),
                BossSeed("Lava Dragon Statue", listOf(113072), listOf(PYRO)),
                BossSeed("Secret Source Automaton: Overseer Device", listOf(113076), listOf(HYDRO)),
                BossSeed("Knuckle Duckle", listOf(113077), listOf(ELECTRO)),
                BossSeed("Radiant Moonfly", listOf(113078), listOf(PYRO, DENDRO)),
                BossSeed("Frostnight Herra", listOf(113079), listOf(HYDRO, CRYO)),
            ).mapIndexed { index, seed -> boss(index, seed, MaterialSourceType.WORLD_BOSS) },
        )
        addAll(
            listOf(
                BossSeed("Stormterror", (113003..113005).toList(), listOf(HYDRO, ELECTRO, ANEMO)),
                BossSeed("Wolf of the North", (113006..113008).toList(), listOf(PYRO, CRYO, GEO)),
                BossSeed("Childe", (113013..113015).toList(), listOf(HYDRO, ELECTRO, CRYO)),
                BossSeed("Azhdaha", (113017..113019).toList(), listOf(PYRO, ELECTRO, GEO)),
                BossSeed("La Signora", (113025..113027).toList(), listOf(PYRO, CRYO)),
                BossSeed("Guardian of Eternity", (113032..113034).toList(), listOf(ELECTRO)),
                BossSeed("Shouki no Kami, the Prodigal", (113041..113043).toList(), listOf(HYDRO, ELECTRO, ANEMO)),
                BossSeed("Guardian of Apep's Oasis", (113046..113048).toList(), listOf(DENDRO)),
                BossSeed("All-Devouring Narwhal", (113054..113056).toList(), listOf(HYDRO)),
                BossSeed("The Knave", (113060..113062).toList(), listOf(PYRO)),
                BossSeed("Lord of Eroded Primal Fire", (113068..113070).toList(), listOf(PYRO)),
                BossSeed("The Game Before the Gate", (113073..113075).toList(), listOf(PYRO, HYDRO, ELECTRO)),
            ).mapIndexed { index, seed -> boss(index, seed, MaterialSourceType.WEEKLY_BOSS) },
        )
        addAll(
            listOf(
                EnemySeed("Slimes", 112002..112004),
                EnemySeed("Hilichurls", 112005..112007),
                EnemySeed("Samachurls", 112008..112010),
                EnemySeed("Hilichurl Shooters", 112011..112013),
                EnemySeed("Fatui Skirmishers", 112032..112034),
                EnemySeed("Treasure Hoarders", 112035..112037),
                EnemySeed("Whopperflowers", 112038..112040),
                EnemySeed("Nobushi and Kairagi", 112044..112046),
                EnemySeed("Specters", 112053..112055),
                EnemySeed("Fungi", 112059..112061),
                EnemySeed("Eremites", 112065..112067),
                EnemySeed("Fontemer Aberrants", 112080..112082),
                EnemySeed("Clockwork Meka", 112083..112085),
                EnemySeed("Natlan Saurians", 112101..112103),
                EnemySeed("Sauroform Tribal Warriors", 112104..112106),
                EnemySeed("Landcruisers", 112122..112124),
                EnemySeed("Fatui Oprichniki", 112125..112127),
            ).mapIndexed(::enemy),
        )
    }

    private fun talentDomain(index: Int, seed: TalentSeed): MaterialSourceSeed =
        MaterialSourceSeed(
            key = key(MaterialSourceType.TALENT_DOMAIN, seed.name),
            name = seed.name,
            type = MaterialSourceType.TALENT_DOMAIN,
            region = seed.region,
            displayOrder = index,
            materials = seed.familyFirstIds.flatMapIndexed { familyOrder, firstId ->
                (firstId..firstId + 2).mapIndexed { materialOrder, materialId ->
                    MaterialSourceMaterialSeed(
                        materialId = materialId,
                        role = MaterialSourceRole.DROP,
                        familyOrder = familyOrder,
                        materialOrder = materialOrder,
                        schedule = MaterialSchedule.entries[familyOrder],
                    )
                }
            },
        )

    private fun boss(
        index: Int,
        seed: BossSeed,
        type: MaterialSourceType,
    ): MaterialSourceSeed = MaterialSourceSeed(
        key = key(type, seed.name),
        name = seed.name,
        type = type,
        displayOrder = index,
        materials = seed.materialIds.mapIndexed { materialOrder, materialId ->
            MaterialSourceMaterialSeed(
                materialId = materialId,
                role = MaterialSourceRole.DROP,
                materialOrder = materialOrder,
            )
        } + seed.gemFamilyFirstIds.flatMapIndexed { familyOrder, firstId ->
            (firstId..firstId + 3).mapIndexed { materialOrder, materialId ->
                MaterialSourceMaterialSeed(
                    materialId = materialId,
                    role = MaterialSourceRole.GEM,
                    familyOrder = familyOrder,
                    materialOrder = materialOrder,
                )
            }
        },
    )

    private fun enemy(index: Int, seed: EnemySeed): MaterialSourceSeed =
        MaterialSourceSeed(
            key = key(MaterialSourceType.ENEMY, seed.name),
            name = seed.name,
            type = MaterialSourceType.ENEMY,
            displayOrder = index,
            materials = seed.materialIds.mapIndexed { materialOrder, materialId ->
                MaterialSourceMaterialSeed(
                    materialId = materialId,
                    role = MaterialSourceRole.DROP,
                    materialOrder = materialOrder,
                )
            },
        )

    private fun key(type: MaterialSourceType, name: String): String =
        "${type.name.lowercase()}:${GoodKeyNormalizer.normalize(name)}"

    private data class TalentSeed(
        val name: String,
        val region: String,
        val familyFirstIds: List<Int>,
    )

    private data class BossSeed(
        val name: String,
        val materialIds: List<Int>,
        val gemFamilyFirstIds: List<Int>,
    )

    private data class EnemySeed(val name: String, val materialIds: IntRange)
}
