# Reusable prompt: generate a character damage-calculator JSON

Copy the prompt below into your model and paste the backend response at the end. This contract reflects the current GenshinApp implementation; refresh the catalogs when the app changes.

Verified character JSON link: http://localhost:47831/api/characters/eula
Equivalent: http://localhost:47831/characters/eula/json
Replace `eula` with the canonical character key and use the app's current port if different. A remote model cannot read your computer's localhost: paste the JSON yourself.

Development-only companion endpoints (ui-dev profile):
- GET /api/damage-formulas/v2/editor/damage-types
- GET /api/damage-formulas/v2/editor/standard-inputs
- GET /api/damage-formulas/v2/editor/catalog
- GET /api/damage-formulas/v2/editor/file?path=eula.json
- Editor: /damage-calculator-v2/editor

The catalog returns parameter keys, labels and numeric level tables only for already-authored characters. The character endpoint supplies readable descriptions/formatted tables, but may omit internal parameter IDs and constellation descriptions.

---

You are creating a reusable character definition for GenshinApp's V2 damage calculator. Use AUTHORING SCHEMA VERSION 3. One file must support both personal damage and teammate effects. Follow the complete contract below. Do not return a compiled schema-2 API response or a separate teammate file.

TASK

Read all supplied attack, talent, passive, constellation, alternate-form, infusion and updated mechanic descriptions. Include Hexerei and Witch's Revelation upgrades when data supports them. Derive scaling and damage classification from descriptions and parameter labels: a Skill talent multiplier can deal Normal Attack damage, and an attack triggered with the Normal Attack button can deal Skill damage.

Preserve existing IDs and user edits if extending a file. Model individual hits, mutually exclusive alternatives, stack/cap conditions, recipients and exclusions accurately. Avoid duplicated totals: label individual hits and any alternative total views clearly. Healing, shields, cooldowns, Energy restoration, duration, attack speed, stamina and defensive resistance are not outgoing damage multipliers. Document them or use informational metrics.

Use talentParameter references for level-dependent scaling, not a single fixed talent-level value. Only special stacks/state controls belong in inputs. Inherited stats, talent levels and constellation already exist.

Do not invent parameter IDs, missing talent levels, constellation descriptions, reaction factors, unsupported expression nodes or new damage-type IDs. If descriptions are missing, use verified research only if authorized in the input; otherwise identify the exact gap and mark the draft incomplete. Do not silently omit mechanics while claiming complete coverage.

OUTPUT

1. Short coverage report and exact data gaps, if any.
2. ONE valid JSON code block for {key}.json, without comments/placeholders/NaN/Infinity.
3. Map generated talent references to supplied labels/tables, and identify any separately required resource or shared-engine change.
4. Concrete validation cases. Claim tests/saving only if actually performed.

A. ROOT AND FILE

Path: src/main/resources/data/damage-formulas-v2/{key}.json.
Required: schemaVersion=3; key; characterKey (same as key); name; element.
key must equal filename without .json. Normalize element spelling: Pyro, Hydro, Electro, Cryo, Dendro, Anemo, Geo. Physical is a supported damage element, not normally a character's native element.

Other authorable root fields:
- weaponType: Sword | Claymore | Polearm | Bow | Catalyst.
- characterDataResource: existing data/characters/data/{key}.json or data/damage-talent-data-v2/{key}.json. Optional/default empty. It is a packaged resource path, not an HTTP URL. Do not name a file that does not exist.
- inputs: special input definitions, default [].
- talents: talent groups, default [].
- effects: effect definitions, default [].
- inputDefaults: numeric preview overrides keyed by inherited standard input IDs, default {}.
- talentParameters: optional numeric level-table map keyed by talent.parameter, default {}.
- statBindings: optional logical-stat -> existing local input ID aliases, mainly for legacy files. Omit for new standard-input profiles; this does not create inputs.
- moonsignContribution: integer, normally 0 unless the character actually contributes. Team sum caps at 2; dealing Lunar damage alone does not prove contribution.
- defaultTeam: canonical character keys, normally [own key]; maximum four selected members including owner.
- notes: string array of assumptions/alternatives/manual states.
- sources: string array of verified resource paths, backend routes or URLs.

Do not author damageTypes inline: character registry rejects them. Choose shared IDs per hit. Do not copy runtime fields: teamMembers, selectedTeam, teamContext, teamStatInputs, supportProfiles, artifactLoadouts, characterStatInputs, characterProgressInputs, characterBuffInputs, calculated results. Runtime sourceCharacterKey, artifactOrigin and currentValueLoaded are not character authoring fields.

B. STANDARD INPUTS / REFERENCES

Every character automatically has:
characterLevel, normalTalentLevel, skillTalentLevel, burstTalentLevel, constellation;
attack, maxHp, defense, elementalMastery, critRate, critDamage, energyRecharge;
baseAttack, baseHp, baseDefense;
elementalDamageBonus (native element), physicalDamageBonus;
pyroDamageBonus, hydroDamageBonus, electroDamageBonus, cryoDamageBonus, dendroDamageBonus, anemoDamageBonus, geoDamageBonus. The native-element alias maps to elementalDamageBonus. For inputDefaults use the actual standard ID from the standard-input catalog, not a native alias absent from that catalog.

Current standard defaults and ranges:
- characterLevel 90 [1..90]; talents 1 [1..15]; constellation 0 [0..6].
- attack/defense 0 [0..20000]; maxHp/baseAttack/baseHp/baseDefense 0 [0..200000].
- elementalMastery 0 [0..5000].
- critRate 5, critDamage 50, energyRecharge 100, all percentage points [0..1000].
- damage bonuses 0 percentage points [0..1000].
- enemyLevel 90 [1..120].
- enemyResistance (native element) and enemyPhysicalResistance 10 percentage points [-200..200].
- defenseReduction and defenseIgnore 0 percentage points [0..100].
Foreign damage types automatically bind enemy<Element>Resistance as needed. Composition can add enemyReaction<Element>Resistance for reaction-only elements. Prefer automatic type binding.

Portable references:
- source:<stat>: character owning this file/effect, including off-field sources.
- recipient:<stat>: actual recipient; for party stat changes each party member separately, for formula modifiers the damage owner.
- local:<id>: special input declared in THIS file.
- battle:<id>: existing shared enemy input, e.g. battle:enemyLevel.

Examples: source:attack, source:skillTalentLevel, source:constellation, recipient:baseAttack, local:veilStacks. Composition supplies runtime namespacing; do not author character.someone.stat IDs manually. Do not invent stats. Ordinary ATK% buffs usually add recipient:baseAttack times a ratio to recipient:attack; HP%/DEF% similarly use baseHp/baseDefense.

C. SPECIAL INPUT

Required: id (unique), label, defaultValue, min, max, step (positive). Numeric values must be finite, min <= defaultValue <= max.
Optional: unit (number | percentPoints | ratio; default number), section (presentation string; prefer combat), description, hintTag, initialValueSource ({valueKey, optional characterKey}), defaultContext (equipment default lookup; omit for character controls).

Example:
{"id":"veilStacks","label":"Veil stacks","defaultValue":0,"min":0,"max":3,"step":1,"unit":"number","section":"combat","description":"Stacks available for this hit."}

Toggle: min 0, max 1, step 1. Input unit is presentation metadata; input nodes consume raw numbers without automatic percent conversion. Enforce mechanical caps with expressions. Avoid section enemy/internal for special controls because teammate binding and visibility treat these specially.

Saved-build valueKey values: level, constellation, normalTalent, skillTalent, burstTalent, totalAttack, baseAttack, totalHp, baseHp, totalDefense, baseDefense, or stat:<existing stat key>. Common stat keys: eleMas, critRate_, critDMG_, enerRech_, pyro_dmg_, hydro_dmg_, electro_dmg_, cryo_dmg_, dendro_dmg_, anemo_dmg_, geo_dmg_, physical_dmg_. Unknown keys do not implement new stats. Usually omit initialValueSource for special controls.

D. TALENTS AND LEVEL TABLES

Talent group shape: {"key":"normal","name":"Actual attack name","formulas":[]}.
Conventional group keys: normal, skill, burst. Additional identifiers are possible, but prefer those three. Empty formulas are valid for non-damaging talents.

Talent expression:
{"type":"talentParameter","talent":"combat2","parameter":"param4","levelInput":"source:skillTalentLevel"}
Optional characterKey selects another character's real loaded table; normally omit it. Another character must actually be loaded with the selected team; do not assume cross-character references work solo.
combat1 uses source:normalTalentLevel; combat2 source:skillTalentLevel; combat3 source:burstTalentLevel. Attack damage classification is independent of that mapping.

Preferred source: supplied raw talents.combatN.attributes.parameters and labels mapping paramN to names, or the character's editor catalog entry.

If the backend provides only formatted tables, do NOT guess paramN from row order. Use the supported inline table bank instead:
"talentParameters":{"combat1.hit1":[0.5,0.54,0.58]}
Reference it with {"type":"talentParameter","talent":"combat1","parameter":"hit1","levelInput":"source:normalTalentLevel"}.
This is a shortened illustration: include EVERY supplied level row. Convert 50% to 0.5, keep flat values unconverted, and split multi-hit/mixed-stat rows into individual named arrays. Preserve precision and disclose display rounding. Do not extrapolate levels. Inline own-character tables take precedence for matching keys; otherwise the resolver reads the packaged resource. Evaluation rounds the chosen level to an integer and clamps to available rows.

If every needed table is inline, characterDataResource may be empty/omitted. A newly proposed resource path requires an actual separate file; report it as another artifact, not as a file magically created by its JSON reference.

E. EVERY EXPRESSION TYPE

Use JSON expression trees, not strings containing equations:
- {"type":"constant","value":123.4}
- {"type":"input","input":"source:attack"}
- talentParameter: above.
- {"type":"teamMax","input":"elementalMastery"}: highest selected-team effective bound stat. Use an existing team stat binding, normally elementalMastery, attack, maxHp or defense; solo/team composition must supply it.
- {"type":"context","input":"hexereiCount"}: character composition context, resolved to a constant before arithmetic.
- {"type":"add","operands":[EXPR,...]}
- {"type":"multiply","operands":[EXPR,...]}
- {"type":"divide","operands":[NUMERATOR,DENOMINATOR]}
- {"type":"min","operands":[EXPR,...]}: cap/minimum value.
- {"type":"max","operands":[EXPR,...]}: floor/maximum value.

add/multiply/min/max require at least one operand; divide exactly two and a nonzero denominator in every allowed state. Nest expressions freely. Subtract with add and multiply by -1. No subtract/power/floor/ceil/clamp/if/comparison/boolean/JavaScript/string-formula node exists. Branch with effects and alternative hits. Percentage conversion belongs to contribution wrappers, not a special expression type.

Supported character context:
teamSize, moonsignLevel, hydroCryoCount;
pyroCount, hydroCount, electroCount, cryoCount, dendroCount, anemoCount, geoCount (include source; absent count=0);
hexereiCount (shared eligibility list; quest completion still needs controls);
pyroElectroOnly (all selected members are Pyro/Electro; separately require both element counts if needed);
sourceHexerei, recipientHexerei (1/0);
recipientPyroElectro (1/0);
sourceNonFontaineAllies (known non-Fontaine teammates excluding source; unknown regions excluded);
onField, offField (source is/isn't damage owner).
Explicit context keys also exist as character.<key>.hexerei, .pyroElectro, .nonFontaineAllies, .onField, .offField.

Unknown context resolves to zero, not a new implemented mechanic. Do not use equipment-only aliases such as sameElementAllies, differentElementAllies, meleeWeapon or rangedWeapon in character expressions. Context is composition-time metadata, not a combat input.

F. DAMAGE FORMULA

Required: id (unique across every talent group), label, damageType (existing shared ID), base (expression).
Optional: tags [], requiredEffects [], excludedEffects [], infusionRule "physicalOnly", damageBonus (contribution), defenseMitigation, resistanceMitigation, crit.
No per-formula conditions field exists: gate hits with requiredEffects/excludedEffects, using effects with conditions. All required effects must be active; every excluded effect must be absent. IDs must exist locally; never require and exclude the same effect.

Example:
{"id":"normal-1","label":"Normal Attack hit 1","damageType":"directPhysical","tags":["normalAttack"],"base":{"type":"multiply","operands":[{"type":"input","input":"source:attack"},{"type":"talentParameter","talent":"combat1","parameter":"param1","levelInput":"source:normalTalentLevel"}]}}
Verify that param1 exists before using this example.

infusionRule: physicalOnly (default: only Physical ordinary Normal/Charged/Plunging hits can be externally infused), overrideable (existing elemental ordinary attacks can also convert), locked (external infusion blocked).
Protected personal conversion: exclusive original/converted variants, converted requires state, original excludes state, converted locked. Do not lock a Physical formula expecting its own generic infusion to ignore that lock.

G. SHARED DAMAGE TYPES

Current IDs and processing:
- directPhysical: ordinary talent kind, Physical, standard DEF, can CRIT, built-in Physical DMG Bonus, includes ordinary other damage bonus.
- directPyro, directHydro, directElectro, directCryo, directDendro, directAnemo, directGeo: corresponding element, talent kind, standard DEF, can CRIT, matching elemental bonus and ordinary other damage bonus.
- lunarBloom (Dendro), lunarCharged (Electro), lunarCrystallize (Geo): lunar kind, bypass DEF, can CRIT, exclude elemental bonus, built-in 6*EM/(EM+2000).
- stellarConductElectro: stellar kind, Electro, bypass DEF, can CRIT, excludes BOTH ordinary elemental and other damage bonus, built-in 6*EM/(EM+2000).
- artifactPhysicalProc: artifactProc kind, independent proc, bypass DEF, cannot CRIT, excludes ordinary bonuses. Equipment-specific; NOT a general character Physical proc type. Ordinary character passive/constellation procs normally use directElement plus untyped tags.

Current Lunar types retain appliesOtherDamageBonus=true; do not assume false or change shared behavior during character authoring. Ordinary support modifiers should target talent kind explicitly when they must not affect Lunar/Stellar damage.

Types do not insert talent scaling or hit counts. Direct Lunar-Charged/Crystallize special reaction factors are NOT automatically inserted just by selecting a type. Add a required factor in the correct layer only if verified for that mechanic; existing Columbina entries include explicit factors. Do not duplicate shared EM scaling.

No stellarSwirl damage-type ID currently exists. Do not substitute Stellar-Conduct for another reaction. Report a needed shared-catalog change separately.

Separate shared-catalog schema: root schemaVersion=2, damageTypes array. Shared type fields: id, element, kind (talent | lunar | stellar | reaction | artifactProc), defenseBehavior (standard | bypass), appliesElementalDamageBonus (bool), allowsCriticalHits (bool), innateDamageBonus (contribution/null), appliesOtherDamageBonus (bool), resistanceInput (existing input ID), independentProc (bool). elementalBonusInput is generated and must not be authored. Character files select only the type ID.

H. TAGS / TARGETS

Tags are case-sensitive free-form strings, not a closed enum. Established tags include normalAttack, chargedAttack, plungingAttack, plungeImpact, elementalSkill, elementalBurst, infusedAttack, multiHit, lunarBloom, lunarCharged, lunarCrystallize, stellarConduct, stellarSwirl, arkhe. Add consistent custom family tags as needed.
plungeImpact = low/high ground impact, not mid-air path. multiHit is descriptive; it never multiplies damage. Arkhe/untyped procs must not get Normal/Skill/Burst tags without evidence. An untyped character proc can still be ordinary directElement damage. An arbitrary tag does not implement arithmetic: noReaction alone does not disable reaction calculations in the current engine.

Every modifier MUST contain targets, even {}.
Target fields, all optional arrays defaulting to []:
- formulaIds: explicit hits. Local IDs are source-personal; that modifier is dropped when source is a teammate. recipient:<formulaId> deliberately targets a specific existing recipient hit; it must exist. Prefer portable tags for team effects.
- requiredTags: ALL must match.
- anyTags: at least ONE must match when nonempty.
- damageKinds: talent | lunar | stellar | reaction | artifactProc.
- elements: Pyro | Hydro | Electro | Cryo | Dendro | Anemo | Geo | Physical.
Fields combine with AND; formulaIds/elements/damageKinds contain alternatives within their own arrays. {} matches all available damage. No wildcard/excludedTags/negation/nested OR target exists. Use separate modifiers for different combinations.

Automatic ordinary reaction results add their reaction ID to the hit's tags. Current IDs: vaporize, melt, aggravate, spread, overloaded, electroCharged, superconduct, swirl, burning, bloom, hyperbloom, burgeon, shatter, freeze, quicken, crystallize. Reaction DAMAGE bonuses MUST target damageKinds:["reaction"]. Elements select reaction output element, e.g. Cryo Swirl. Do not recreate ordinary reactions as talent hits just to request automatic reaction math. A tag cannot create a reaction family absent from the engine/catalog.

I. EFFECT

Required: id, name, source, description, activation. IDs unique within the character.
Optional fields:
- category: default passive; recommended passive | talent | team | constellation. Backend accepts any nonempty descriptive string; category is presentation, not eligibility/scope.
- impact: optional presentation string.
- defaultEnabled: bool, default true. Existing constellation previews often use false, but ownership gating is ALWAYS required separately.
- ownershipRequirements: map from input reference to minimum numeric value, default {}. Example {"source:constellation":3}. ID/category alone does not gate C3 ownership.
- requiresEffects: local prerequisite IDs, all required.
- recipient: self | party | others | activeCharacter | enemy, default self.
- excludeSource: bool, default false.
- statChanges [], formulaModifiers [], metrics [].
- stackingGroup: optional string for mutually suppressing copies.
- stackingPriority: optional expression; highest eligible priority wins in a group, default priority 0; deterministic ID tie-breaking.
- effectTags: descriptive string array, not executable behavior.

self = file owner; party = all selected members; others = all except source; activeCharacter = current damage owner; enemy = enemy debuff affecting current damage owner. Components can override recipient/excludeSource. Enemy scope is for mitigation modifiers, not character statChanges. Off-field source stats can be changed and then used by its team buffs; personal hit modifiers do not leak to the damage character.

Prerequisites must exist locally, cannot depend on self, and must converge with stacking selection. Do not name another character's runtime effect ID as a local dependency. A constellation upgrading a passive must replace/enhance it, not double it: use shared stackingGroup/priority or disjoint gates.

J. ACTIVATION / CONDITIONS

Activation shape: {"event":"buffActive","talentKey":"optional group identifier","conditions":[]}.
Suggested event values: always, buffActive, burstActive, burstFieldActive, skillActive, skillUsed, burstUsed, normalAttackHit, chargedAttackHit, reactionTriggered, teamComposition.
Event is descriptive only; no closed backend enum or event timeline exists. Merely writing burstUsed does not test an action, consume stacks or simulate duration. Use conditions/state inputs/dependencies.

ONLY condition types:
{"type":"inputAtLeast","input":"local:stacks","value":2} => >=
{"type":"inputAtMost","input":"local:stacks","value":2} => <=
{"type":"teamMember","teamMember":"canonical-key"} => member selected
{"type":"contextAtLeast","input":"hexereiCount","value":2} => context >=

Condition arrays are AND. Equality uses >= and <= together. No inputEqual/inputAbove/inputBelow/contextAtMost/contextEquals/anyOf/not/expression threshold or input-to-input comparison exists. Strict <50 differs from <=50; use a meaningful explicit toggle or accurate threshold for the allowed control range. Comparing changing values can require a manual state input.

contextAtLeast accepts existing context keys and binds aliases onField/offField/sourceHexerei/recipientHexerei/recipientPyroElectro. sourceNonFontaineAllies is an EXPRESSION alias, not a condition alias; do not assume an automatic expansion there. Prefer portable aliases, with manual inputs when needed.

Conditions may appear on activation, statChanges, formulaModifiers and metrics. Component requiresEffects add prerequisites. Combat conditions read REQUESTED inputs, not newly modified effective stats. A stat change cannot be relied on to activate a later condition.

K. STAT CHANGE

Required: stat, operation, value (expression).
Optional: conditions [], requiresEffects [], minimum number, maximum number, phase integer (default 0), recipient override, excludeSource bool.

operation:
- flatAdd: add expression's raw amount.
- percentAdd: add phase-start CURRENT stat times value/100. Value is percent points, NOT a contribution wrapper. Usually wrong for ordinary game ATK%/HP%/DEF% bonuses, which use base stats.

Use recipient:<logicalStat> for party buffs. source:<logicalStat> or local:<controlId> may only change self. Target must already exist. minimum/maximum clamp the resulting stat, not the amount added.

Each phase reads one immutable phase-start snapshot; writes accumulate without recursively feeding expressions in that same phase. Phases run in increasing order. Usually phase 0 for ordinary stats/levels, later phase 10 for conversions that must read buffs first. Existing Bennett field uses phase 1. Pick actual mechanical order rather than blindly reusing phase numbers.

Examples:
- 20% ATK buff: recipient:attack, flatAdd, multiply(recipient:baseAttack, constant 0.2).
- EM share: recipient:elementalMastery, flatAdd, capped source:elementalMastery expression.
- +3 Skill levels: recipient:skillTalentLevel, flatAdd, constant 3, maximum 15, constellation ownership gate.

critRate/critDamage/energyRecharge/damage bonus stats use PERCENTAGE POINTS (20 = 20%). ATK/HP/DEF/EM use raw values. baseDamageBonus is not a stat: it is a formula modifier field.

L. EVERY FORMULA MODIFIER LAYER

Required targets object; optional conditions [], requiresEffects [], recipient override, excludeSource. Multiple changes can share the same modifier if targets/conditions match.

Supported changes:
- base: expression, flat additive base damage.
- baseDamageBonus: contribution, summed percentages in one (1+sum) base group.
- baseDamageMultiplier: contribution, EACH matching contribution creates an independent (1+contribution) factor on completed base.
- damageBonus: contribution, additive damage bonus bucket.
- postBonusBase: expression, flat damage after damage bonuses but before DEF/RES/Elevation/CRIT.
- defenseMitigation: {reduction?:CONTRIBUTION,ignore?:CONTRIBUTION,bypass?:boolean}.
- resistanceMitigation: {reduction?:CONTRIBUTION}.
- crit: {additionalRate?:CONTRIBUTION,additionalDamage?:CONTRIBUTION}.
- elevation: contribution, summed into (1+sum).
- infusion: {damageType:ORDINARY_SHARED_TYPE_ID,weaponTypes:[...],priority:INTEGER}.

Contribution: {"expression":EXPRESSION,"unit":"percentPoints"} or unit ratio; default percentPoints. +20% = constant 20 percentPoints OR constant 0.2 ratio. Flat base/postBonusBase/statChange values do NOT take that wrapper.

EXACT ORDER:
B=formula.base, A=sum modifier.base, P=sum baseDamageBonus ratios, I=product(1+each baseDamageMultiplier ratio), D=applicable elemental/other/innate/authored damage bonuses, X=sum postBonusBase, E=sum elevation ratios.
Ordinary/non-Lunar completed base = [B*(1+P)+A]*I.
Lunar-kind completed base = [(B+A)*(1+P)]*I.
Noncritical = [completedBase*(1+D)+X]*DEFMultiplier*RESMultiplier*(1+E).
Critical = noncritical*(1+CRIT_DMG).
Average = noncritical*(1+clamped_CRIT_RATE*CRIT_DMG).

For (B+x+y)*(1+f+g)*(1+z), use base additions x/y and TWO baseDamageMultiplier contributions: one add(f,g), another z. Do not put every percentage in baseDamageBonus. A +20% independent contribution means x1.2, not x0.2. Post-bonus additions sit outside those factors.

DEF reductions sum then clamp 0..90%; DEF ignore sums SEPARATELY and is NOT currently clamped by the implementation, so never produce invalid combined ignores. Bypass forces DEF multiplier=1. Standard DEF multiplier = (characterLevel+100)/[(characterLevel+100)+(enemyLevel+100)*(1-reduction)*(1-ignore)].
RES reductions sum; type selects native/foreign RES. Effective RES <0: multiplier 1-RES/2; else RES <0.75: 1-RES; otherwise 1/(4*RES+1). CRIT Rate clamps 0..1 and CRIT DMG to at least zero.

Direct Stellar-Conduct gets EM bonus through shared D. A field reaction multiplier can use baseDamageMultiplier. Verified reaction additive damage such as Cyno A4's 600% EM uses postBonusBase. Choose layers by actual mechanics, not the word "bonus" alone.

M. INFUSIONS

weaponTypes defaults to Sword/Claymore/Polearm. Valid strings: Sword, Claymore, Polearm, Bow, Catalyst; select only eligible weapons. priority defaults 0. Type must be ordinary talent kind and not independentProc. Infusion changes ordinary Normal/Charged/Plunging damage before bonus/RES/reaction selection, preserving formula ID and scaling. Locks apply.

Highest priority wins; conflicting different types at equal priority error. Existing steady-state priorities: Chiori/Dori 0; Chongyun 5; Bennett/Diluc 10; Candace 20. If Hydro would win and Cryo is also eligible, the engine returns Frozen-weapon Cryo. It does not simulate weapon gauges, reactions over time or refresh timings.

N. METRICS / NON-EXECUTABLE FIELDS

Metric fields: label, expression, unit (number | percentPoints | ratio; default number), conditions []. They display evaluated values, and do not modify damage or implement healing/rotation/DPS simulation.
Presentation strings such as label/name/source/description/impact/hintTag/section are not arithmetic enums. Custom tags/event/category names do not create new parser capabilities.

O. ROOT OUTLINE

Replace the outline with actual data. It is not a complete character:
{
  "schemaVersion":3,
  "key":"characterkey",
  "characterKey":"characterkey",
  "name":"Character Name",
  "element":"Pyro",
  "weaponType":"Sword",
  "characterDataResource":"",
  "inputs":[],
  "inputDefaults":{"normalTalentLevel":10,"skillTalentLevel":10,"burstTalentLevel":10},
  "talentParameters":{},
  "talents":[
    {"key":"normal","name":"Actual attack name","formulas":[]},
    {"key":"skill","name":"Actual Skill name","formulas":[]},
    {"key":"burst","name":"Actual Burst name","formulas":[]}
  ],
  "effects":[],
  "defaultTeam":["characterkey"],
  "notes":[],
  "sources":[]
}

P. VALIDATION AND DELIVERY

Audit all IDs, inherited/local references, talent tables, constellation ownership, state gates, recipients and replacement stacking. Validate both solo and teammate composition. Check: C0/relevant constellation thresholds/C6; special inputs at zero/min/max; safe division; resource depletion/caps; exclusive forms/toggles off/dependencies missing; correct classification vs talent-level source; source/intended/excluded recipient; ordinary vs Lunar/Stellar; infusion eligibility/locks; quest incomplete/second Hexerei absent/present; enemy debuffs before/after trigger; HP threshold boundaries; independent percentages and flat additions in the correct layer.

With repository access use DamageFormulaServiceV2.validateCharacter(key), calculate solo/composed profiles and focused numeric regression checks. The editor Validate button also checks team calculations.

Development API: POST /api/damage-formulas/v2/editor/validate with {"path":"characterkey.json","content":"ENTIRE JSON SERIALIZED AS A STRING"}. The target file must ALREADY exist: this is not a create-file endpoint. POST /api/damage-formulas/v2/editor/save additionally requires the revision from GET /api/damage-formulas/v2/editor/file?path=characterkey.json and validates before writing. Do not invent a GET validation route or claim save/test success without running it.

Do not silently modify shared damage types, team rules, equipment files or Kotlin parser code to make a draft valid. Identify any separately needed shared change and its verified requirements. Clearly disclose incomplete coverage and display-precision tables.

INPUT DATA TO PROCESS

Character backend JSON:
<PASTE GET /api/characters/{key} RESPONSE>

Precise talent resource/catalog entry, if available:
<PASTE DATA OR SAY NONE>

Existing character formula, if extending:
<PASTE FILE OR SAY NEW>

Missing constellation / updated mechanic descriptions:
<PASTE SUPPLEMENT OR SAY NOT PROVIDED>

Existing precise resource path:
<EXISTING PATH OR NONE>

Shared catalog overrides, if different:
<PASTE CURRENT CATALOGS OR SAY USE CONTRACT ABOVE>

Research authorization:
<VERIFIED CURRENT RESEARCH ALLOWED / PROVIDED DATA ONLY>
