# Hand-authored damage profiles (V2)

The active character's damage instances live in `<character>.json`. Selected
teammates supply support definitions from `teammates/<character>.json`. These are
reviewed formulas, not an automatic interpretation of character descriptions.
The original `data/damage-calculators` implementation remains independent.

## Adding a teammate

1. Add a schema-version-2 JSON file under `teammates/`. Files are discovered on
   application startup; there is no character registry in the page JavaScript.
2. Set `key`, `name`, `element`, `weaponType`, and `moonsignContribution`. Record sources and
   limitations in `sources` / `notes`; the page displays these.
3. Give inputs globally unique, character-prefixed IDs. `initialValueSource`
   reads the selected character's owned build. The composer supplies its
   `characterKey`. A missing imported value retains the fallback without a
   “Current” marker. Use `section` and reusable `hintTag` metadata for presentation.
4. Map logical stats to input IDs in `statBindings`, e.g.
   `"elementalMastery": "laumaElementalMastery"`. Shared team rules and `teamMax`
   expressions use these bindings, not hard-coded teammate names.
5. Define support `effects` using the same nullable formula layers as the carry.
   Use category `team` for buffs and `constellation` for constellation effects.
   Every constellation has a card, including those with no per-hit support effect;
   their descriptions explicitly distinguish personal damage and utility.
6. Use `ownershipRequirements` to define constellation defaults. Explicit toggles
   can disable owned effects or preview unowned effects. Ownership is not a lock.
7. Add calculation tests, including activation/dependency-off cases and a team
   removal check. Only selected characters' inputs and effects enter calculation.

Support files deliberately do not implement the teammate's personal damage
rotation. To make that character the damage target, author a complete top-level
damage profile as well. Do not apply personal buffs to another carry merely
because the source character is present.

## Expressions and damage layers

Expressions support `constant`, `input`, `talentParameter`, `teamMax`, `add`,
`multiply`, `divide`, `min`, and `max`. `talentParameter` optionally specifies
`characterKey`; the level comes from `levelInput` at calculation time. Lauma uses
the bundled talent tables. If no bundled table exists, a support file can hold
explicitly reviewed `talentParameters` keyed by `talent.parameter`, as Columbina
does. References returned by the endpoint retain source character and talent level.

- `base`: adds to the talent base before `baseDamageBonus` (Nefer C1).
- `baseDamageBonus`: additive percentage contributions to a base multiplier.
- `damageBonus`: adds to the ordinary/reaction bonus layer selected by damage type.
- `postBonusBase`: flat addition **after** damage bonuses (Lauma's Pale Hymn).
- `defenseMitigation`, `resistanceMitigation`: nullable reduction/ignore fields.
- `crit`: additional CRIT Rate / CRIT DMG; effective rate is clamped to 0–100%.
- `elevation`: separate additive percentage contributions to the final multiplier.

Current pipeline:

```
Base = (talent base + sum(base)) × (1 + sum(baseDamageBonus))
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

Each definition contains `inputs`, `effects` and optional `damageTypes`. An effect
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
healing and resource consumption are not simulated. The team files cover support
effects relevant to Nefer's Dendro/Lunar-Bloom hits. Other reaction branches must
be authored before claiming support for those damage forms. Artifact reaction
modifiers are ready to target those future branches, not a claim that every
reaction base formula is implemented. Weapon passives are not inferred from a
character name. Columbina's Max HP and base HP remain
explicit manual inputs because the current build catalog cannot resolve them.

Mechanics references: [Lunar Reaction Guide](https://keqingmains.com/misc/lunar-reactions/),
[Lauma](https://library.keqingmains.com/characters/dendro/lauma),
[Columbina](https://keqingmains.com/q/columbina-quickguide/).
Character-specific provenance is also recorded in each support file.
Set mechanics and trigger details: [Artifact reference](https://library.keqingmains.com/equipment/artifacts).
