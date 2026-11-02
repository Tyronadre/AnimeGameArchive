# Unifying V2 character and teammate definitions

Status: implemented. This document records the migration rationale and design. Character sources now use schema 3; the V2 calculation response remains the compiled interface. The old teammate files have been removed. Damage type definitions have subsequently moved to the shared `damage-types.json` catalog; each hit selects a type ID.

## Pre-migration findings

There are 9 complete damage profiles and 11 support profiles, representing 12 characters. Eight characters have both definitions. Lauma, Nahida, and Columbina currently have support definitions only. Nefer currently has a damage profile without a separate support profile.

The files are not interchangeable:

- The full profile defines its own damage types, formulas, inputs, personal effects, and default party.
- Team composition reads support inputs and effects from `teammates/*.json`, rather than deriving them from full profiles.
- References such as `attack` mean the selected damage character in one place, while `bennettBaseAttack` identifies the support in another.
- Effects and dependencies have different IDs between versions (`fantasticVoyageField` versus `bennettFantasticVoyage`).
- Some definitions differ in actual behavior, not just names. Citlali C2 includes a self EM bonus in both versions but a separate recipient EM bonus only in the support definition. Furina C2 changes her HP in the full definition but is informational in the support definition.
- Equipment binding and talent-table lookup use `supportProfiles`. Formula Studio also explicitly distinguishes teammate paths.

Consequently, concatenating the two files would produce duplicate effects, conflicting inputs, and incorrect recipients.

## Proposed source model

Use one authored character definition per character. Keep the existing top-level file location initially to avoid an unrelated path migration. Adopt an explicit new authoring schema version, with a compatibility compiler that continues producing the current V2 calculation document.

The character definition owns:

- Identity, element, weapon type, Moonsign contribution, notes, and sources.
- Character inputs and logical stat bindings.
- A talent-data resource and/or explicit talent parameter tables.
- Optional talent damage formulas, with references to the shared damage type catalog.
- One canonical list of effects containing personal, party, on-field, and enemy changes.
- Optional default party.

Damage formulas can be absent. Support-only characters remain usable as teammates and are marked **Support only** in the editor. The damage-character picker is enabled only when damage formulas exist; file existence alone must no longer imply calculator availability. This avoids inventing personal damage formulas for Lauma, Nahida, or Columbina merely to move their definitions.

Do not keep a second embedded `supportEffects` copy of shared effects: that would relocate the duplication rather than remove it.

## Separate source, recipient, and hit selector

The file establishes the source character. Each effect has a default recipient of self, and individual stat changes / formula modifiers can override it.

| Recipient | Meaning |
|---|---|
| `self` | Source character |
| `party` | Every selected party member |
| `others` | Every selected party member except the source |
| `activeCharacter` | The character currently on field |
| `enemy` | A debuff on the shared enemy, applied to matching calculated hits |

Recipient selection needs an optional source exclusion for effects affecting only other on-field characters. More specific states, such as being shielded, remain explicit conditions. Party-wide does not imply a buff is active: trigger toggles, conditions, and effect dependencies still apply.

Recipient rules belong on individual changes, with effect-level defaults for convenience. Citlali C2 cannot be expressed faithfully by assigning a single recipient to the whole effect: its existing definitions describe +125 EM to self, +250 EM to the eligible recipient, and a separate enemy RES change.

`targets` still answers which damage instances match (element, damage kind, tags, formula IDs). It must not be overloaded to identify who owns a buff.

The current calculator assumes the selected damage character is on field. Keep that assumption initially, state it in the UI, and model `activeCharacter` separately internally so calculating off-field damage can be added later without another schema rewrite.

## References and namespacing

Make source files use local identifiers and explicit logical references:

| Authored reference | Meaning |
|---|---|
| `local:<id>` | An input/control in this character definition |
| `source:<stat>` | A logical stat belonging to the effect source |
| `recipient:<stat>` | The corresponding stat on each resolved recipient |
| Talent reference without an explicit character | The source character's talent table |
| `teamMax:<stat>` semantics | Existing team-stat aggregation, evaluated with the same phase snapshots |

For example, Bennett's field becomes one definition: change the active recipient's ATK by **source Base ATK × source Burst scaling**. The same definition works when Bennett is the chosen damage character or a teammate.

During composition, the compiler resolves local IDs, ownership requirements, conditions, talent-level references, formulas, metrics, stacking priority, and dependencies to unique runtime IDs. The existing equipment binder already performs much of this work for `wearer:`, `recipient:`, and `local:` references; extract a shared binder rather than implementing a second subtly different expression walker.

Preserve the existing calculation engine's required core input aliases for the selected damage character during the first migration. Use unique names for the other characters. Avoid storing aliases as duplicate independent stats: stat changes and `teamMax` must see each character exactly once.

Enemy settings (level, element resistances, manual DEF reductions/ignore) remain one shared battle context. Do not namespace or copy them as personal stats for every teammate. Existing generic `enemyResistance` references need mapping to the selected damage element's resistance.

Personal formula-ID selectors are scoped to the source's damage instances. Never reinterpret a support's `normal-1` as the chosen damage character's `normal-1`. Source-only hit modifiers can be omitted from the chosen character's hit calculation while retaining that source's stat changes.

## Composition order

1. Load the selected characters from the unified registry.
2. Bind each member's inputs, progress, stat bindings, talent tables, and effect IDs.
3. Establish shared battle inputs, active character, and team context.
4. Resolve effect activation, recipient eligibility, dependencies, and non-stacking groups.
5. Apply stat changes to all eligible members using the existing immutable phase snapshots.
6. Attach and bind equipment against the same member registry and stat bindings.
7. Evaluate only the requested damage owner's hit formulas with eligible character and enemy modifiers.
8. Return the existing calculation breakdown and per-character effective stats.

Equipment effects must join the same activation/stat-phase pipeline as character effects. The order above describes binding dependencies, not a separate pass that applies equipment stats after character conversions.

Do not discard a source's personal stat changes simply because its own hits are not being calculated. Nahida's personal EM changes can affect the amount she shares. Likewise, stat changes on other supports can feed equipment buffs and team maxima. Preserve phase numbers and stacking rules to avoid recursive conversion or double buffs.

## Migration sequence

1. **Capture current inputs and outputs.** Record representative solo and party calculations, default/disabled effects, constellations, and equipment. Record intentional existing profile discrepancies separately; do not silently choose one version or edit user-authored formulas to satisfy old expectations.
2. **Add the source model and shared binder.** Keep the current source loaders working through an adapter. Recipient defaults must preserve legacy self-only behavior.
3. **Prove one dual-role character.** Merge Bennett first: source Base ATK and talent scaling, recipient ATK, C1/C6 field dependencies, and his personal damage. Check him both as the selected character and as a teammate.
4. **Merge the remaining eight full profiles and three support-only definitions.** Compare each duplicate effect by name, source, expression, target, condition, and dependency. Assign canonical IDs; map old IDs only where unambiguous. Preserve current edits and flag genuine behavior differences for explicit resolution.
5. **Switch consumers.** Team composition, build loading, talent-table lookup, equipment binding, roster capability detection, editor file listing/catalog/validation, and reference pickers must all use the unified registry. Runtime support metadata can remain a derived compatibility view temporarily; it must not be another authored source.
6. **Update Formula Studio.** One entry per character; add recipient controls to effects and individual changes, source/recipient stat pickers, talent-data controls, and Support-only / Damage-enabled badges. Explain the on-field assumption. Preserve the existing modifier layer choices, including independent base multipliers.
7. **Remove teammate files and old loaders only after parity checks.** Reject conflicting leftover definitions rather than loading both. Refresh open calculator/editor sessions after the schema switch; do not reinterpret stale submitted IDs as another character's inputs.

## Checks required before removing the old files

- Every migrated character produces its intended own damage when selected, where damage formulas exist.
- Every party buff applies once when its source is selected or supporting.
- Personal hit buffs do not leak to other characters.
- Personal source stat changes still feed the correct support conversions and equipment effects.
- Active-character buffs do not raise every off-field teammate's stats.
- Citlali-style mixed-recipient effects resolve each component independently.
- Removing a teammate removes all its effects, including stale request toggles.
- Reordering a party does not change results.
- Dependency IDs, formula IDs, and input IDs cannot collide between characters.
- Support-only characters remain selectable as teammates but do not offer an empty personal calculator.
- Shared enemy settings, constellation previews, phase ordering, stacking suppression, independent base multipliers, equipment, Current markers, and live editor reload remain correct.

## Primary code touch points

- `DamageFormulaModelsV2.kt`: authored versus compiled models; recipient metadata; support-only capabilities.
- `DamageFormulaTeamServiceV2.kt`: unified registry and composition.
- `DamageFormulaArtifactServiceV2.kt`: extract reusable binding; replace authored-support lookups.
- `DamageFormulaServiceV2.kt`: source talent lookup and validation against compiled references.
- `DamageFormulaBuildServiceV2.kt`: preserve each character's build-source binding.
- `DamageFormulaEditorV2Controller.kt` and `formula-editor-v2.js`: unified file catalog, recipient controls, and validation.
- `damage-calculator-v2.js`: consume derived member metadata and capability flags.
