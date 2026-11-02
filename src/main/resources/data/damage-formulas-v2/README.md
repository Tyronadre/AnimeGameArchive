# Unified character formulas (authoring V3, calculator V2)

Each character has one `<character>.json` definition containing both personal damage and support effects. There are no authored teammate files. Character sources use `schemaVersion: 3`; the composer compiles them to the existing V2 calculation response. Artifact, weapon, reaction, and shared-rule formats remain version 2.

Damage types are defined once in `damage-types.json`. Character and artifact files reference their IDs on individual damage instances; they do not contain a `damageTypes` array. The loader includes the types each profile actually uses. Lauma, Nahida, and Columbina currently have support-only definitions without talent formulas. They remain valid teammates; the damage-character picker becomes available only when a definition contains damage formulas.

## Shared damage types

`damage-types.json` defines the element, damage kind, DEF behavior, CRIT eligibility, damage-bonus eligibility, enemy resistance input, and optional built-in scaling for each reusable type. Ordinary types cover all seven elements and Physical; Lunar-Bloom and independent artifact damage have their own rules. A hit saves only `"damageType": "directPyro"`, for example. Its stat/talent scaling, attack tags, and per-hit modifiers remain on the hit.

In Formula Studio, choose a type from a damage instance's **Damage type** dropdown. Use **Edit shared damage types**, or open `damage-types.json` in the library, to edit the shared rules. Changes affect every consumer. Validation checks unused type expressions as well and prevents removing types that any formula references.

Shared scaling reads logical stats such as `source:elementalMastery` and `source:physicalDamageBonus`; it must not depend on character-specific control IDs. The loader binds these to each character's inputs. The damage type's element selects its elemental damage bonus and resistance, including hits whose element differs from the character's. Main-element damage keeps the existing bonus and enemy RES controls.

## Weapon definitions

`weapons/<normalized-weapon-key>.json` defines equipped weapon passives using
schema version 2. Supported weapons currently include Reliquary of Truth,
Nightweaver's Looking Glass, A Thousand Floating Dreams, and Nocturne's Curtain
Call. Base ATK and secondary stats still come from the saved build; these files
add only passive effects, avoiding duplicate equipment stats.

Define `inputs`, `effects` (each containing a `recipient` and an `effect`), and
reference URLs in `sources`. Recipients are `wearer`, `party`, or `others`.
The shared equipment binder resolves `wearer:<stat>`, `recipient:<stat>`,
`local:<input>`, and team composition `context` expressions exactly as for
artifact effects. Inputs and effect dependencies are namespaced as
`weapon.<character>.<weapon>.<id>`. Refinement is initialized from the equipped
copy and can be previewed from R1 to R5 in its weapon section. Only selected
party members' equipped weapons contribute.

Trigger controls describe an active combat snapshot: durations, cooldowns,
rotations, and energy spending are not simulated. Nocturne exposes energy per
proc as a metric. HP bonuses add a percentage of character base HP, and Lunar
CRIT bonuses target the Lunar damage kind. Nightweaver's party bonus uses one
shared stacking group with the highest active refinement winning; Floating
Dreams' party EM from different wearers stacks.

## Adding or editing a character

1. Create/edit one root character file with `schemaVersion: 3`, matching `key` and `characterKey`, identity, and effects. Add talent formulas when personal damage has been authored, selecting a shared `damageType` ID for each hit. Define `inputs` only for special controls; the list may be empty or omitted.
2. `CharacterStandardInputsV3` supplies character level, all three talent levels, constellation, combat/base stats, elemental bonuses, and shared enemy controls for every character. Optional `inputDefaults` changes preview values by logical name without copying definitions; owned builds supply actual values. `statBindings` is needed only to retain or customize an input ID.
3. Effects default to `recipient: self`. Choose `party`, `others`, `activeCharacter`, or `enemy` when appropriate. Each stat change or formula modifier can override the effect default. `excludeSource: true` excludes the source character as a recipient.
4. Read an owned stat with `source:elementalMastery`, a recipient stat with `recipient:baseAttack`, and a character control with `local:<input-id>`. A stat-change target such as `recipient:attack` is bound separately for each recipient. Local/source stat-change targets must use a Self recipient to avoid repeated source changes.
5. Talent references default to the source character. Their level input uses `source:normalTalentLevel`, `source:skillTalentLevel`, or `source:burstTalentLevel`; existing `local:<id>` aliases remain valid. Both bundled character tables and explicitly authored `talentParameters` are supported.
6. Ownership requirements, conditions, effect dependencies, metrics, and expressions are bound to unique character IDs during composition. Do not store compiled `character.<key>.<id>` references in source files. Enemy input reads can use `battle:<id>` and remain shared rather than copied into each teammate's stats.
7. Hit selectors retain their meaning. Local formula IDs belong to the source character; they cannot accidentally select another character's same-named hit. Tag/kind/element selectors are preferred for party buffs.
8. Preserve the existing immutable stat phases and genuine non-stacking groups. Personal stat changes remain active for sources whose own hits are not being calculated, since those stats can feed party buffs and equipment.

The chosen damage character is currently treated as on field. This calculator evaluates a combat snapshot, not a rotation. Enemy debuffs belong in formula mitigation modifiers, not in character stat changes.

Formula Studio shows one entry per character, support-only capability badges, default and per-change recipient controls, and portable source/recipient stat pickers. Its saved JSON remains the authored definition; runtime support metadata in API responses is derived, never another source file.

## Expressions and damage layers

Expressions support `constant`, `input`, `talentParameter`, `teamMax`, `add`,
`multiply`, `divide`, `min`, and `max`. `talentParameter` optionally specifies
`characterKey`; the level comes from `levelInput` at calculation time. Lauma uses
the bundled talent tables. If no bundled table exists, a character definition can hold
explicitly reviewed `talentParameters` keyed by `talent.parameter`, as Columbina
does. References returned by the endpoint retain source character and talent level.

- `base`: adds to the talent base before `baseDamageBonus` (for example, Nefer C1).
- `baseDamageBonus`: additive percentage contributions to a base multiplier.
- `baseDamageMultiplier`: independent percentage contributions. Each matching modifier applies its own `(1 + contribution)` factor to the completed base, including flat base additions.
- `damageBonus`: adds to the ordinary/reaction bonus layer selected by damage type.
- `postBonusBase`: flat addition **after** damage bonuses (Lauma's Pale Hymn).
- `defenseMitigation`, `resistanceMitigation`: nullable reduction/ignore fields.
- `crit`: additional CRIT Rate / CRIT DMG; effective rate is clamped to 0–100%.
- `elevation`: separate additive percentage contributions to the final multiplier.

Current pipeline:

```
Independent = product(1 + each baseDamageMultiplier)
Talent Base = (talent base × (1 + sum(baseDamageBonus)) + sum(base)) × Independent
Lunar Base = (talent base + sum(base)) × (1 + sum(baseDamageBonus)) × Independent
Non-CRIT = (Base × DamageBonus + sum(postBonusBase)) × DEF × RES × Elevation
CRIT = Non-CRIT × (1 + effective CRIT DMG)
Average = Non-CRIT × (1 + effective CRIT Rate × effective CRIT DMG)
```

Contributions use `percentPoints` unless explicitly marked `ratio`.
`targets.requiredTags` require all listed tags; `anyTags`, `formulaIds`, `elements`,
and `damageKinds` select alternatives within their lists. Selectors combine with
AND. An empty selector matches all carry formulas.
Always scope elemental RES and elemental CRIT buffs by `elements`.
Use damage kind `talent` for ordinary damage, `lunar` for Lunar reactions,
`reaction` for separately authored other reactions, and `artifactProc` for
independent artifact damage. Normal/Charged/Skill tags describe damage
classification, not the talent table that supplies the multiplier: Nefer's
Phantasm Performance uses Skill-level multipliers but Charged Attack damage tags.

## Activation and stat conversion

`activation.event` documents the assumed trigger; it is not a rotation simulator.
The toggle asserts that this buff's trigger/duration is satisfied. Machine-checked
`conditions` gate inputs, team members, or derived team context. `contextAtLeast`
can use `moonsignLevel`, `teamSize`, or element counts such as `dendroCount`.
`requiresEffects` on an effect, stat change, or individual modifier handles
dependencies. For example, Columbina C6's CRIT buff needs Lunar Domain, but its
Elevation does not. Formula `requiredEffects` / `excludedEffects` support C6 hit
replacement without character branches in the engine.

Stat changes run in numbered `phase`s. All changes in one phase read the same
immutable snapshot. Phase 0 contains flat stats and talent-level changes; phase 10
contains stat-to-stat conversions. Thus Nahida C4 and resonance precede her EM
share, while Nahida's share and Columbina's HP-to-EM buff do not feed one another
recursively. The response exposes each resolved stat change and its references;
the UI uses these for exact origin/amount tooltips.

`team-rules.json` holds shared rules. A stat-change target like
`team:elementalMastery` expands to each selected member's stat binding.

## Artifact set definitions and equipment

`artifacts/<normalized-set-key>.json` contains one hand-authored definition for
each of the 61 sets in the bundled catalog. No descriptions or optimizer's
assumed maximum bonuses are parsed into formulas. The coverage test compares
the catalog keys and evaluates every definition on the carry and a teammate at
1, 2, 4, and 5 equipped pieces.

Each definition contains `inputs` and `effects`. Extra formulas select a damage type from the shared catalog. An effect
wrapper declares its required `pieces` (1/2/4), `recipient` (`wearer`, `party`, or
`others`), an ordinary V2 `effect`, and optional extra damage `formulas`.
All ordinary nullable formula layers remain available. Utility, healing,
resource, and cooldown effects can have no damage modifier: their descriptions
and numeric `metrics` explain what changes without inventing a damage multiplier.
Recovery metrics labelled "base" precede healing modifiers; this calculator
does not simulate healing rotations or resource spending.

Artifact expressions bind portable names when equipment is attached:

- `input: "wearer:elementalMastery"` reads the set wearer's effective EM.
- `input: "recipient:baseAttack"` reads each buff recipient's base ATK.
- `input: "local:stacks"` refers to an input belonging to this copy of the set.
- `type: "context", input: "sameElementAllies"` becomes the actual selected
  team count, excluding the wearer. `differentElementAllies`, `moonsignLevel`,
  `onField`, `offField`, `meleeWeapon` and `rangedWeapon` are also available.
- Local conditions use `input: "local:..."`; derived conditions use
  `type: "contextAtLeast", input: "context:..."`. An input's `defaultContext`
  can initialize its field-state toggle without hard-coding a wearer name.
- `targets.elements` can include `wearer` or `recipient`, resolved to elements.

For example, an ATK percentage is a **flat addition based on base ATK**, never
a percentage of total ATK:

```json
{
  "stat": "attack",
  "operation": "flatAdd",
  "value": {
    "type": "multiply",
    "operands": [
      { "type": "input", "input": "recipient:baseAttack" },
      { "type": "constant", "value": 0.2 }
    ]
  }
}
```

Stat changes expand to the declared recipients. Formula modifiers affect the
carry only when the carry is a recipient. A support's self-only damage bonus
remains visible as a metric but cannot leak into the carry's hit. IDs and local
controls become `artifact.<wearer>.<set>.<id>`. The endpoint supplies
`artifactLoadouts` and `artifactOrigin` for per-character categories and piece
counts; clients cannot activate an unavailable tier by submitting `true`.
Unknown equipped sets are explicitly marked unsupported instead of receiving
guessed bonuses.

Use `stackingGroup` only for genuinely non-stacking effects. One eligible source
wins per group; `stackingPriority` can select the strongest source from its input
expression (for example, Scroll's enhanced bonus or Song's recorded healing).
Dependencies and stacking resolve together; a suppressed effect cannot enable
its dependent bonus. Intent and Chant each contribute their own unique 10%
party Lunar bonus, not 20% per wearer. Flat set EM is applied before phase-10 EM
sharing, and Emblem reads effective ER after its 2-piece bonus.

Extra formulas use the same per-hit pipeline. Ocean-Hued Clam demonstrates an
`independentProc`: DEF bypass, no CRIT/elemental/other DMG bonus, and a separate
`enemyPhysicalResistance` input. Only matching RES modifiers can affect it.
Echoes and Song instead add to the existing talent's `base` before damage bonuses.
Proc toggles and recorded-healing inputs describe a snapshot, not average uptime.

Owned artifacts (including assignment, level, stats, lock and crafting metadata)
are persisted in `player_artifact_instance`, scoped by user and import position.
`player_artifact_inventory` marks a completed migration, including an intentionally
empty inventory. Existing GOOD/override files are imported once; migration leaves
the original files intact. Later reads/edits use the database. A fresh GOOD import
explicitly replaces that user's inventory. Existing inventory APIs and ordering
are preserved.

V2 loads build stats with `includeSetBonuses = false`, then applies the selected
set effects exactly once. The legacy optimizer/calculator keep their existing
set assumptions. Baseline inputs and the Current marker refer to the equipped
build **before set bonuses**; the summary displays effective stats. Known base
ATK is loaded from character/weapon data. Missing base HP/DEF values remain
explicit manual inputs without a Current marker, rather than guessed values.

## Endpoints and current scope

- `GET /api/damage-formulas/v2/nefer`: default party and owned build defaults.
- `GET .../nefer?team=nefer,lauma`: explicitly composed party.
- `POST .../nefer/team`: `{teamKeys, inputs, enabledEffects}` returns a composed
  document and calculation while retaining edits for remaining inputs/effects.
- `POST .../nefer/calculate`: same request shape, calculation only.

The carry is always included and there are at most four distinct party members.
Unknown members are rejected. Inputs/effects belonging to removed members cannot
affect damage, even if a stale client submits them.

This is a per-hit snapshot: Pale Hymn depletion, buff duration, reaction timelines,
healing and resource consumption are not simulated. The character definitions cover support
effects relevant to Nefer's Dendro/Lunar-Bloom hits. Other reaction branches must
be authored before claiming support for those damage forms. Artifact reaction
modifiers are ready to target those future branches, not a claim that every
reaction base formula is implemented. Weapon passives are not inferred from a
character name. Columbina's Max HP and base HP remain
explicit manual inputs because the current build catalog cannot resolve them.

Mechanics references: [Lunar Reaction Guide](https://keqingmains.com/misc/lunar-reactions/),
[Lauma](https://library.keqingmains.com/characters/dendro/lauma),
[Columbina](https://keqingmains.com/q/columbina-quickguide/).
Character-specific provenance is also recorded in each character file.
Set mechanics and trigger details: [Artifact reference](https://library.keqingmains.com/equipment/artifacts).

Pyro and Hydro resonance use base ATK/HP with a full party and two matching elements.
Cryo resonance requires the explicit Cryo/Frozen enemy toggle. DEF reduction is capped
at 90% after stacking. Ordinary Furina and Citlali buffs exclude Lunar damage.
Fanfare inputs represent current total points; C1 raises the cap to 400 without
adding its starting points a second time. Bennett C1/C6 require his active field.

## Formula studio (UI development)

Start with `bootRun -PuiDev` or `desktopRun -PuiDev`, then open
`/damage-calculator-v2/editor` (also linked as **Edit formulas** on the v2 page).
The structured forms edit inputs, damage types, talents, effects, expressions,
weapon/artifact definitions, and shared rules. Add optional fields using the field
selector. No JSON text editing is required.

Validate checks the proposed document against the formula engine and team
composition. Save validates again, checks for concurrent disk edits, writes the
source file atomically, and invalidates formula caches. Refresh the calculator to
use the saved definitions. Unsaved edits are guarded when switching files or
leaving the editor. The editor is available only with the `ui-dev` profile and
requires the repository root as the working directory.

### Independent base multiplier example

```json
{
  "targets": { "damageKinds": ["lunar"] },
  "baseDamageMultiplier": {
    "unit": "percentPoints",
    "expression": { "type": "constant", "value": 20 }
  }
}
```

This adds a separate ×1.2 factor. A second matching modifier with 30 percentage
points adds ×1.3 (combined ×1.56). To add percentages within one independent
factor, use an `add` expression in a single contribution. The existing
`baseDamageBonus` contributions still sum together. Independent base multipliers
do not affect `postBonusBase`; those flat amounts are added later.
