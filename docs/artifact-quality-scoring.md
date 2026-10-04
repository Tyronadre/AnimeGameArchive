# Artifact quality for a build plan

Individual quality is independent of equipped items, weapon bonuses, other
artifacts and the character's current stat totals. It describes useful substat
value relative to an attainable perfect +20 five-star artifact with the same
main stat. Main-stat suitability and set membership are displayed separately.

For each substat `s`, divide its actual value by its maximum five-star roll and
multiply by the build weight:

```text
weightedRolls = sum(weight[s] * value[s] / maximumRoll[s])
bestPossibleRolls = sum(topFourEligibleWeights) + 5 * highestEligibleWeight
quality = 100 * weightedRolls / bestPossibleRolls
```

Eligible substats exclude the main stat. Four distinct initial substats plus
five upgrades into the highest-weight stat form the reference. Missing useful
stats have weight zero. A plan valuing only EM has a reference of six EM rolls,
so its perfect flower can reach 100. An EM main stat in that same plan has no
useful substat budget; the UI explains this instead of implying a division by
zero or a poor main stat. The score is clamped to 0–100.

The reference does not shrink for low levels or three-line starters. A starter
with excellent first rolls therefore has a low current score and useful final
potential. Lower-rarity pieces are compared with the same five-star reference.
Main-stat level and rarity remain visible alongside quality.

Built-in profiles retain role-specific weights. The ATK profile values CR and CD
at 1.0, ATK% at 0.8, ER at 0.5 and EM at 0.45. Its flower reference is 8.3
weighted rolls. The example 10.1% CR, 20.2% CD, 9.3% ATK and 5.2% ER scores 82.8.
Custom plans use `0.85^(priorityIndex)`; minimum-only stats receive weight 0.4.
Adding a minimum for an already selected stat does not change its quality weight.
Imported profiles preserve their listed substat order, followed by remaining
goal stats. These weights are heuristic importance, not damage percentages.

Explicit main-stat alternatives are retained as `|`-separated stat keys through
the form, saved profiles, named profiles and shared profiles. Every allowed
alternative has full compatibility. Disallowed main stats are labeled
unsuitable and excluded from loadout, farming and leveling suggestions.
Built-in profile recommendations retain their preferred/viable labels.
Matching a main stat grants no fixed quality bonus.

Pieces show target-set or off-piece membership. Set requirements are checked
when assembling the complete loadout; an off-piece keeps its substat score.

Complete builds retain minimum goals and explicitly entered maximum constraints.
Imported recommended upper ranges remain source guidance, with CR capped at
100%. After constraints and minimum completion, loadout comparisons use weighted
stat value and `1 + clamp(CR, 0, 1) * CD` for expected CRIT, with decimal inputs.
The logarithmic CRIT gain is calibrated into approximate roll units at a 50/100
reference build. This allows tradeoffs between stats instead of strict priority
ordering; it is a heuristic loadout objective rather than a full rotation model.
The average displayed quality uses the individual substat scores. Slots with no
useful substat budget are labeled unscored and excluded from the average; missing
slots still reduce it. Thus an EM-only plan with three EM main stats can receive
full substat quality from its flower and plume.

Unfinished equipped five-star pieces and leveling candidates show expected +20
quality and the 10th–90th percentile final range from 800 deterministic upgrade
simulations. The projection's random seed excludes equipped stat totals,
ownership, set membership and manual bonuses. Existing evaluation cache entries
are bypassed with scoring version 3.

Game-mechanics references:

- [KQM artifact guide](https://keqingmains.com/misc/artifacts/)
- [KQM damage formula](https://library.keqingmains.com/damage/)
