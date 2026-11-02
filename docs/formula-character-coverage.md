# Character formula coverage

This document tracks the reviewed character definitions and their implemented
damage, passive, and constellation mechanics. The initial batch upgraded the
former support-only definitions for Lauma, Nahida, and Columbina while preserving
existing character effects and portable input bindings. Further alphabetical
batches are recorded below; characters outside these batches remain to be authored.

## Alphabetical batch: Aino through Amber

Added five definitions, cross-checked against `/api/characters/{key}` and the
corresponding KQM character references:

| Character | Damage and conditional mechanics |
| --- | --- |
| Aino | Physical attacks, Hydro Skill stages, Burst balls, flat EM passive scaling, C2 proc snapshot, talent upgrades, EM share and C6 reaction bonuses |
| Albedo | Physical attacks, ATK Skill cast, DEF Transient Blossoms, Burst/Fatal Blossom hits, EM share, Hexerei buffs and enhanced constellations |
| Alhaitham | Physical/Dendro infusion alternatives, mixed ATK/EM Skill, individual Projection rays, one Burst instance, EM passive and constellation snapshots |
| Aloy | Physical/Cryo alternatives, charged arrows, bomb/bomblets, Burst, Coil tiers, Rushing Ice and personal/party ATK effects |
| Amber | Physical/Pyro arrows, C1 secondary arrows, ordinary/manual Bunny explosion alternatives, Burst wave/total alternatives, passive and constellation buffs |

Albedo includes Hexerei and Silver Isotoma enhancements. Aether has no combat
talent table in the identity file and needs separate element-specific Traveler profiles.

Multi-hit Projection variants and Amber's Burst wave/total are alternative views,
not values to sum together. Aino C2 explicitly represents an off-field trigger
snapshot. Aloy has no unlockable constellation effects.

References: [Aino](https://library.keqingmains.com/characters/hydro/aino),
[Albedo](https://library.keqingmains.com/characters/geo/albedo),
[Alhaitham](https://library.keqingmains.com/characters/dendro/alhaitham),
[Aloy](https://library.keqingmains.com/characters/cryo/aloy),
[Amber](https://library.keqingmains.com/characters/pyro/amber).

### Albedo Hexerei conditions

`team-rules.json` contains the shared eligible-character list. Composition derives
`hexereiCount` and each selected member's eligibility. Secret Rite requires two
eligible members and Albedo's Homework control. Other eligible members are
assumed to have completed Homework; Secret Rite can be disabled for an unfinished
teammate. Solar/Silver creation buffs use separate toggles because their duration
can outlast the constructs. Silver's extra buff only reaches eligible Hexerei recipients.

Silver presence adds 240% DEF to Transient Blossom's base, independently of the
low-enemy-HP passive. C1 adds 50% base DEF while Rite is active. C2 has a separate
off-field proc snapshot, displayed as one of three DEF-scaled blossoms. C4's
Silver-jump buff applies only to tagged plunge ground impact. C6 has independent
controls for constructs destroyed by the current Burst and the subsequent Fatal
Blossom buff, so a destroyed construct need not still be present.

The calculator evaluates these snapshots; it does not automatically generate or
destroy constructs, consume stacks, or simulate cooldowns. Base-kit calculations
remain available without Rite. Reference:
[Albedo Hexerei guide](https://keqingmains.com/q/albedo-quickguide/).

## Alphabetical batch: Itto through Beidou

Added Arataki Itto, Baizhu, Barbara, and Beidou, using current stored character
descriptions and bundled talent references, with current constellation/passive
details cross-checked against the corresponding KQM references.

- Itto: physical/Geo attack alternatives, Ushi, Burst DEF-to-ATK conversion after
  ordinary stat bonuses, Kesagiri flat DEF addition, and constellation buffs.
  Burst itself has no direct hit. Combo availability and random stack preservation
  are snapshots rather than simulated attack sequences.
- Baizhu: split Normal hits, Skill/Sprite: Splice, Spiritveins, HP-dependent
  personal Dendro bonus, capped reaction buffs, party EM, talent upgrades, and C6
  HP addition. The reaction buff asserts a recent Seamless Shield heal; it does
  not require the Burst still to be active, since C6 can generate Shields too.
- Barbara: Hydro attacks and initial Skill droplets, Melody Loop availability,
  active-character Hydro bonus and talent upgrades. Healing ticks and Burst
  healing are not represented as damage instances.
- Beidou: Tidecaller power, Stormbreaker initial/discharge hits, C4's untyped
  Electro proc, personal attack bonus, and C6 resistance reduction. The initial
  Burst hit does not receive its newly applied C6 shred. Each lightning entry is
  one hit, not an assumed total across enemies.

Beidou includes Witch's Revelation: the power control represents enemy hits or
power accumulated by holding. Quest completion and external Polestar Field state
gate Radiance and its C6 Cryo resistance/active-character EM additions. Skill
cooldown and Energy refunds do not multiply outgoing damage. Automatic Stellar
reaction generation and field duration are not simulated by this character file.

References: [Itto](https://library.keqingmains.com/characters/geo/arataki-itto),
[Baizhu](https://library.keqingmains.com/characters/dendro/baizhu),
[Barbara](https://library.keqingmains.com/characters/hydro/barbara),
[Beidou](https://library.keqingmains.com/characters/electro/beidou).

## Bennett C6 and reusable weapon infusion

Bennett C6 now uses a generic infusion modifier, rather than duplicated personal
attack formulas. Fantastic Voyage and C6 eligibility gate it. Sword, Claymore,
and Polearm attack classifications can become Pyro, while Skills/Bursts and
protected personal conversions retain their elements. The separate +15 Pyro
damage stat bonus applies to any active recipient inside the field, including
Bow and Catalyst users, as verified in the
[Bennett mechanics reference](https://library.keqingmains.com/characters/bennett/).

Converted hits retain their IDs/scaling and resolve bonuses, resistance, reactions,
and UI badges with their effective damage type. Current Itto, Alhaitham,
Arlecchino, and Skirk conversion variants are explicitly protected. Other
overridable elemental attacks can opt in via the formula's infusion rule.

## Lauma review

- Preserved the authored normal-attack parameter references and Sanctuary mixed scaling.
- Corrected Press to Dendro Skill damage scaling with ATK, and the first Hold hit to ATK.
- Corrected the three plunge classifications to Plunging Attack.
- Held Lunar damage scales with EM and Verdant Dew consumed, capped at three.
  Zero Dew makes Held damage unavailable. Moon Song is not the damage count.
- Added Cleansing for the Spring's capped personal Skill bonus and individual C6 procs.
- Burst has no direct damage instance. The existing authored Pale Hymn damage
  layer is preserved; this review does not change the calculator's damage-layer conventions.

## Nahida

Includes Normal/Charged/Plunging attacks, Press/Hold Skill, mixed ATK/EM Tri-Karma,
Awakening Elucidated, Shrine of Maya's Pyro bonus including C1's extra count,
C3/C5 talent levels, and a C6 Karmic Oblivion hit. Burst has no direct damage.
Electro/Hydro Burst effects alter frequency and duration rather than per-hit damage.

## Columbina

Includes Normal/Charged/Plunging Hydro attacks, three individual Moondew Cleanse
hits, Skill cast and Gravity Ripple, five individual Lunar-Bloom Interference
hits, Lunar-Charged and Lunar-Crystallize Interference alternatives, and Burst.
Lunacy stacks modify personal CRIT Rate. C3 raises Skill level; C4 adds personal
Interference scaling. Existing Lunar support selectors cover the Lunar kind.

Character data was read from the running application's
`GET /api/characters/{key}` endpoint. Lauma and Nahida use the existing precise
bundled talent parameters, cross-checked against stored descriptions and tables.
Columbina's stored display-precision tables are exported to
`data/damage-talent-data-v2/columbina.json`; formulas reference these parameters.
Stored table lengths are preserved and the existing resolver clamps to the last
available row. No missing talent levels are extrapolated. The pre-existing
explicit `combat3.lunarBonus` support table remains unchanged.

Direct Lunar-Charged and Lunar-Crystallize reaction factors are explicit constants
in the corresponding base expressions (3 and 1.6); their C4 additions include the
same factor. These alternatives are separate selectable hits, not simultaneous
damage from one proc. Resource depletion, cooldowns, uptime, hit limits and
rotations are not simulated. Existing C2/C6 support effects retain their documented
Lunar-Bloom-dominant snapshot assumptions.

References: [Lauma](https://library.keqingmains.com/characters/dendro/lauma),
[Columbina](https://library.keqingmains.com/characters/hydro/columbina),
[Lunar mechanics](https://keqingmains.com/misc/lunar/).

## Alphabetical batch: Candace through Chiori

Added four complete character definitions, bringing authored damage profiles to 25.
All damage scales through the bundled talent parameters, except fixed passive and
constellation multipliers stated in the character descriptions.

- Candace: split physical attacks, HP-scaled Skill releases, Burst cast/swap waves,
  toggleable Hydro infusion, elemental Normal Attack bonuses, C2 HP, talent levels,
  and a separate C6 off-field wave snapshot. Her C6 trigger control must be enabled
  after another character's attack; her own attacks cannot trigger it.
- Charlotte: Cryo attacks, untyped Arkhe thorn, press/hold photos, individual mark
  ticks, Burst cast/camera ticks, C2 ATK, C4 marked-target bonus and C6 coordinated
  Burst damage. Her Cryo passive counts selected non-Fontainian teammates using
  their region metadata; Chiori counts as Inazuman despite her Fontaine shop.
- Chevreuse: physical attacks, press/hold/Overcharged shots, untyped Arkhe blade,
  grenade/shell hits and individual C2 explosions. The 40% Pyro/Electro RES debuff
  requires both elements and rejects teams with a third element. Her HP-scaled ATK
  buff caps at 40% of recipient base ATK and only reaches Pyro/Electro recipients,
  without imposing the exclusive-composition restriction. C6's active-recipient
  heal stacks add up to 60% Pyro and Electro DMG Bonus independently of composition.
- Chiori: split physical attacks, toggleable/overrideable Geo infusion, mixed
  ATK/DEF Skill and Burst, Tapestry and individual Kinu hits, talent levels and C6's
  flat DEF addition to Normals. Tapestry/Tailoring are mutually exclusive. C1 with
  another Geo teammate can grant Finishing Touch without a construct; the two
  activation paths never double its 20% bonus.

The infusion priorities represent a steady-state snapshot: Candace Hydro (20)
beats Bennett Pyro (10), which beats Chiori Geo (0). Weapon gauge reactions and
refresh timing are not simulated. Healing, cooldowns, extra dolls and proc counts
are described but are not multiplied into each individual damage instance.

The editor context menus also expose `sourceNonFontaineAllies`,
`recipientPyroElectro`, and `pyroElectroOnly` for these portable effects.

References: [Candace](https://library.keqingmains.com/characters/hydro/candace),
[Charlotte](https://library.keqingmains.com/characters/cryo/charlotte),
[Chevreuse](https://library.keqingmains.com/characters/pyro/chevreuse),
[Chiori](https://library.keqingmains.com/characters/geo/chiori),
[Candace infusion evidence](https://library.keqingmains.com/evidence/characters/hydro/candace),
[Chiori guide](https://keqingmains.com/q/chiori-quickguide/).

## Alphabetical batch: Chongyun through Cyno

Added Chongyun, Clorinde, Collei, and Cyno, bringing coverage to 29 characters.
Stored descriptions and precise bundled talent parameters are used for damage
scaling. Current passive/constellation descriptions were cross-checked with KQM;
Cyno's updated Starsame text was also checked against the live Paimon.moe entry.

- Chongyun: physical attacks, toggleable melee Cryo infusion, Skill cast and
  Rimechaser, individual Burst blades, untyped C1 ice blades, talent upgrades and
  conditional additive C6 bonus. Rimechaser's initial hit excludes its newly applied
  10% Cryo RES debuff; subsequent self and teammate hits can receive the debuff.
  Extra blades, attack speed, cooldown reduction and Energy restoration affect
  counts/timing rather than each hit's damage.
- Clorinde: split physical combo, Skill-parameter Swift Hunt/Impale variants
  classified as locked Electro Normal Attack damage, untyped Arkhe, individual
  Burst hits and C1/C6 Shade hits. A1 caps at 1,800; C2 replaces it with a 2,700
  cap. Each individual Pact/Burst hit receives its own flat addition. Lawful
  Remuneration uses persistent stacks; C4 uses Bond of Life when Burst deals damage,
  including the Bond granted by that Burst. C6's CRIT buff can outlast Night Vigil.
- Collei: physical and Dendro arrows, outgoing/returning ring hits, individual
  Sprout ticks, Burst explosion/leaps, off-field C1 ER, talent upgrades, C4 EM for
  other characters, and untyped C6 proc. C2 removes the reaction prerequisite for
  Sprout, while still requiring the ring to return. Base/C2 Sprout entries are
  mutually exclusive. Lunar-Bloom qualifies for the reaction-trigger snapshot.
- Cyno: ordinary attacks/Skill and protected Pactsworn conversions using Burst
  talent parameters, EM state bonus, Judication and separate bolts, A4 flat EM
  scaling, constellation effects and Witch's Revelation. Quest completion,
  external field presence, current field tier and Together We Rise are explicit
  snapshots. C1's EM can transfer to the active teammate and persist outside the
  field. C2's additional Stellar bonus only reaches that current recipient's
  Stellar-Conduct damage. C6 swaps its ordinary bolt for Starsame while Together
  We Rise is active.

Shared `stellarConductElectro` is available in the structured editor, along with
Stellar damage selectors. It bypasses DEF and ordinary elemental/common damage
bonuses, can CRIT, and has the shared 6×EM/(EM+2000) bonus. Starsame deals 200% ATK;
its 600% EM A4 addition uses `postBonusBase`, after the reaction damage bonus and
before CRIT/RES/Elevation. The external Polestar Field control supplies its current
1–2 reaction multiplier, 20–40% Cryo/Electro bonus and 40% Physical RES reduction.
This does not automatically create a field or simulate its application counter.

Infusion resolution now handles the Hydro/Cryo Frozen-weapon interaction as a
steady-state Cryo result, while Bennett's Pyro overrides Chongyun alone. Transient
weapon-gauge timing, rotations, cooldowns, proc counts and stack consumption remain
manual snapshots.

References: [Chongyun](https://library.keqingmains.com/characters/cryo/chongyun),
[Chongyun proc evidence](https://library.keqingmains.com/evidence/characters/cryo/chongyun),
[Clorinde](https://keqingmains.com/q/clorinde-quickguide/),
[Collei](https://library.keqingmains.com/characters/dendro/collei),
[Cyno](https://library.keqingmains.com/characters/electro/cyno),
[Cyno updated text](https://paimon.moe/characters/cyno),
[Stellar damage formulas](https://keqingmains.com/misc/stellar-reaction-guide/).

## Alphabetical batch: Dahlia through Diona

Added Dahlia, Dehya, Diluc, and Diona, bringing coverage to 33 characters.
Each definition was checked against the running database endpoint and precise
bundled talent parameters, with current constellation/Revelation text verified
against the linked character references.

- Dahlia: split physical attacks, Sacramental Shower explosion and Burst cast,
  talent upgrades, Favonian Favor and capped HP-based Attack Speed metrics. His
  Skill and Burst damage scale with ATK. Shield renewals, Benison, speed and revive
  mechanics do not multiply damage per hit. C6 shows its additional speed separately.
- Dehya: physical attacks, both Skill casts, mixed ATK/HP field ticks, individual
  Burst punches and final kick. C1 adds base HP first, then uses effective HP for
  every Skill/Burst addition. C2's conditional 50% bonus only applies to the next
  coordinated field hit. C6 CRIT is Burst-only and uses preceding critical-punch
  stacks, capped at four. Punches count as Burst damage, not Normal Attack damage.
- Diluc: physical attacks, toggleable Pyro infusion, three separate Skill slashes,
  Dawn slash/travel tick/explosion, Phoenix's Pyro bonus, talent upgrades and
  conditional constellation bonuses. C2 uses base ATK; C4 only buffs the eligible
  next combo slash; C6 requires one of the next two Normal Attack buff charges.
  Candace can override his infusion; Chongyun alone cannot. Gauge timing is not
  simulated. Phoenix travel tick counts depend on the target and are not summed.
- Diona: physical/Cryo arrows, individual paws, Burst cast/ticks, talent upgrades,
  additive C2 Skill bonus and C6 recipient HP conditions. Choice Treasures adds a
  separate reaction-triggered paw snapshot after Revelation; each trigger fires
  three paws without particles or shields. Revelation's C6 HP bonus is permanent
  while unlocked. Its 40% Superconduct/Stellar-Conduct or Cryo Swirl/Stellar Swirl
  bonus requires the matching Radiance state and actual Signature Mix field,
  independently of recipient HP. The ordinary 200 EM requires strictly above 50%
  HP and can linger for two seconds; a separate inside-field control distinguishes
  that linger from the reaction buff. Incoming healing is shown as a metric.

The editor's tag suggestions now include Stellar Swirl for Diona's portable
reaction selectors. No direct Stellar damage is invented for Diona herself.
Defensive/healing effects, timings, proc counts and stack consumption remain
explicit snapshots rather than an automatic combat simulation.

Also fixed teammate input composition: authored controls beginning with `enemy`
(such as Diluc's C1 enemy HP condition) remain available. Only inputs in the shared
enemy section are omitted from a teammate's local input list.

References: [Dahlia](https://library.keqingmains.com/characters/hydro/dahlia),
[Dehya](https://library.keqingmains.com/characters/pyro/dehya),
[Diluc](https://library.keqingmains.com/characters/pyro/diluc),
[Diona](https://library.keqingmains.com/characters/cryo/diona).

## Alphabetical batch: Dori through Eula

Added Dori, Durin, Emilie, and Eula, bringing coverage to 37 characters.

- Dori: split physical attacks, Skill shot/individual Service Round, Burst
  connector, untyped C2 Jinni Toop, continuous ER-scaled Energy restoration metric,
  talent upgrades, C4 connection buffs and toggleable C6 Electro infusion. C4 HP
  and Energy conditions are independently and strictly below 50%. Extra projectiles,
  healing and cooldown refunds affect counts or utility rather than damage per hit.
- Durin: physical attacks, separate Purity/Darkness Skill follow-ups, mutually
  exclusive Burst forms and individual dragon hits. White form applies the matching
  elemental RES debuffs; Dark form increases Vaporize/Melt damage. Homework plus a
  second eligible Hexerei upgrades A1 to 35% shred or 70% reaction bonus. A4 caps at
  75% of original periodic talent base and does not multiply C1's flat addition.
  C1 has separate stack requirements for active teammates in White form and Durin's
  Burst in Dark form. C2 buffs each qualifying element once; C4 adds Burst damage;
  C6 separates personal DEF ignore from the White-form enemy DEF debuff.
- Emilie: physical attacks, cast, alternative Level 1/2 puffs, untyped Arkhe and
  Cleardew, and individual Level 3 Burst dew. Rectification requires a Burning
  target and caps at 36%. C1 affects Skill-classified hits and Cleardew, excluding
  Arkhe. C2 is a separate existing-debuff snapshot. C6 converts Normal/Charged hits
  to protected Dendro with a flat 300% ATK addition, leaving plunges unaffected.
- Eula: split physical attacks, Press/Hold, individual Icewhirl Brands, Cryo Burst
  cast, stack-scaled Physical Lightfall and the half-base Shattered Lightfall from
  consuming two Grimheart. Current/consumed Grimheart and lingering debuffs are
  separate controls. C1 Physical bonus, talent upgrades and C4's conditional bonus
  cover their correct hit types. C6 floors the inclusive Lightfall total at five;
  it never adds another five to a total already containing them. Total stacks cap
  at the referenced Burst maximum; random C6 stack generation remains manual.

Durin's precise cached game talent parameters are bundled under
`data/damage-talent-data-v2/durin.json` (rather than adding a character identity to
an unrelated catalog). All 344 parameter/display comparisons match the stored
`GET /api/characters/durin` tables. The structured editor can resolve and select
these references through its existing dedicated talent-data resource support.

Hits, active buffs, stack availability and target debuffs are snapshots; durations,
rotation ordering, proc counts, stack depletion and random preservation are not
simulated. Existing character files and custom authoring are preserved.

References: [Dori](https://library.keqingmains.com/characters/electro/dori),
[Durin](https://keqingmains.com/q/durin-quickguide/),
[Emilie](https://keqingmains.com/q/emilie-quickguide/),
[Emilie formula implementation](https://github.com/frzyc/genshin-optimizer/blob/master/libs/gi/sheets/src/Characters/Emilie/index.tsx),
[Eula](https://library.keqingmains.com/characters/cryo/eula).
