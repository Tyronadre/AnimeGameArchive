# Damage calculator v2: weapon definitions

The library contains one schema-2 file for each of the **252 weapons** in the bundled HoYoWiki catalogue: 75 five-star, 143 four-star, 24 three-star, and 10 starter weapons. Definitions live in `src/main/resources/data/damage-formulas-v2/weapons/` and can be edited in Formula Studio.

Each definition links its HoYoWiki source. The four existing definitions for A Thousand Floating Dreams, Reliquary of Truth, Nightweaver's Looking Glass, and Nocturne's Curtain Call were retained. Crimson Moon's Semblance now has its Bond of Life damage bonuses.

## Using the files

- The equipped weapon supplies its refinement. Weapon base ATK and secondary stats already come from the build importer and are **not added again** by these files.
- Unconditional passives apply automatically. Conditional effects expose named toggles, counters, or state selectors. Set them to the situation being calculated; conditional controls normally start inactive.
- Percent ATK, HP, and DEF bonuses use the recipient's base stat. Flat conversions from HP, EM, or ER are calculated in a later stat phase, after ordinary bonuses.
- All-element and general damage bonuses target ordinary damage. Lunar, Stellar, and ordinary reaction bonuses have explicit targets. Hexerei and Moonsign checks use party context where available.
- Team composition is automatic for matching/different elements, distinct elements, Liyue membership, and Natlan-or-different-element membership. Maximum Energy capacity, quest progress, aura conditions, and time-dependent combat states use manual controls.
- Party handoffs such as Thrilling Tales target the selected active character, excluding the weapon holder. Party percentage buffs use the recipient's base stat. Explicitly non-stacking buffs and Millennial Movement's shared ATK buff use stacking groups.

## Separate weapon damage

Weapon effects may now contain `formulas`, using the same expression format and shared damage types as character hits. The editor exposes these formula lists too.

Weapon procs are separate Physical hits tagged `weaponProc` and `noReaction`, with infusion locked. They appear when the weapon's wearer is the selected damage character, so that the wearer's stats and CRIT apply. A teammate's proc is inspected by selecting that teammate as the damage character. The displayed result is one proc or tick, not a rotation total or a probability-weighted proc rate.

Physical proc treatment follows the [KQM damage formula reference](https://library.keqingmains.com/combat-mechanics/damage/damage-formula). Halberd's incomplete bundled refinement text is resolved using the [KQM polearm reference](https://library.keqingmains.com/equipment/weapons/polearms); Messenger's guaranteed critical proc uses the [KQM bow reference](https://library.keqingmains.com/equipment/weapons/bows).

## Scope of the damage model

This is a combat snapshot. It does not simulate energy recovery, healing, shields breaking, attack speed, movement, cooldown resets, proc probability, stack consumption, buff durations, or rotations. Those passive details remain in the descriptions. Weapons whose passives only provide those utilities have a descriptive effect and no invented damage bonus. The ten starter weapons have no passive effects.

Stellar modifiers select the corresponding hit tags; they do not create a missing character damage formula or reaction family. Damage to an individual weak point, a particular cursed target, or an enemy aura must be selected explicitly. Buffs with independent windows have separate controls. For timed buildup such as The Daybreak Chronicles, set the remaining buff amount using the counter after accounting for hits, elapsed time, and Hexerei's faster buildup.

## Maintaining and checking the catalogue

`node scripts/generate-weapon-formulas-v2.cjs` generates the files from explicit combat recipes and the bundled HoYoWiki snapshot at `build/generated/bundled-catalog/data/bundled-catalog-snapshot.json`. It fails when a new catalogue weapon has no recipe. It rewrites recipe-managed files; transfer intentional editor changes into the recipe before regenerating. The four retained definitions are excluded from generation.

`node scripts/generate-weapon-formulas-v2.cjs --check` compares without writing. `WeaponCatalogueFormulasV2Test` validates every file for active and support wearers at R1 and R5 with minimum and maximum controls. Numerical regression checks cover base-stat bonuses, HP/ER conversions, attack classifications, nonlinear stacks, party handoffs, and Physical procs.
