# LiveSync hooks

Register a Spring `@Component` implementing `LiveSyncHook` and implement
`onChange(LiveSyncChange)`. Spring `@Order` controls listener order.
The change contains the local user ID, capture session ID, observation time,
previous/current snapshots, and material counts before and after each change.
Missing material keys count as zero; deltas include removals and spending.

Hooks run synchronously after a successful Irminsul snapshot save. The first
snapshot of each capture session establishes a baseline. Identical snapshots,
manual imports, and manual inventory edits do not invoke hooks. Comparisons use
the previous capture, so manual edits cannot manufacture live material gains.
Failures are logged per hook and do not stop capture or subsequent listeners.
Keep hooks quick. Delivery is best effort, with no persistent retry queue.

`WeeklyBossCollectionHook` uses weekly boss source memberships from the material
catalog, excluding shared gems. It records one `WEEKLY_BOSS_COLLECTED` recent
activity entry per boss per update when that boss's unique material family has a
positive net gain. English and German dashboard messages display the collection.
Conversions within a family have no net gain and are ignored.

Detected claims also update the dashboard's weekly reward counter and today's
resin budget on the next activity poll, even without another inventory revision.
Claims are persisted separately from the short activity feed and deduplicated by
boss and game week. Existing weekly boss entries in recent activity are imported
automatically. Game days and Monday resets use the existing domain schedule.
The counter shows the first three discounted claims; further detected bosses use
60 resin instead of 30. Anonymous manual claims and detected claims are reconciled
using the larger count for each day, so manually recording the same collection
does not count it twice. Reset today clears manual entries, not detected claims.

The dashboard also excludes farming recommendations for bosses detected as
collected during the current game week, using their unique drop IDs from the
catalog. This affects the initial page and live plan refreshes. Bosses become
eligible again at the weekly reset even if the inventory has not changed.
Anonymous manual claims never suppress recommendations for a particular boss.

## Ley lines and domains

`LeyLineCollectionHook` and `DomainCollectionHook` share a conservative reward
bundle detector. Mora/EXP ley lines use known reward ranges. Talent/weapon domains
use gains from one material family, requiring multiple tiers and reward Mora when
no resin consumption is visible. Family decreases reject crafting. Artifact
rewards require new, unlevelled artifacts including a five-star non-boss set;
consumed/upgraded artifacts, strongboxing, and elixir-crafted items are rejected.
Known boss material/gem gains, mixed domain families, and primogem rewards prevent
attribution. Artifact rewards are recorded generically: snapshots cannot identify
the physical domain or distinguish identical rewards from Stygian Onslaught.

These are inferred reward collections, not proof of the activity. Quests, mail,
shops, or event rewards can occasionally mimic a bundle; unusual or mixed batches
can be missed. Domain detection depends on the available material catalog.

Daily claims are saved per user in `farming-claims.json`, independently of the
activity feed, with an event identity to deduplicate retries. Separate captures of
the same activity count again. The dashboard updates run counts and known resin
usage on each poll and resets daily totals using the existing game-day schedule.
Ley lines and domains remain repeatable and are not hidden after a collection.
Artifact run counts remain generic rather than being assigned to every set card.

Resin costs are recorded only when a 20/40/60 Original Resin decrease or one
Condensed Resin consumption accompanies the reward bundle. Condensed consumption
is tracked as a 60-resin equivalent, not another charge at crafting time. Costs
not established by the snapshot are shown as unconfirmed and excluded from the
resin budget. Manual and detected counts use the larger count per task/day, with
generic artifact claims reconciled against manually recorded artifact runs.

Reward reference: [Ley Line Outcrop reward table](https://genshin-impact.fandom.com/wiki/Ley_Line_Outcrop).
Current condensed-resin value: [HoYoverse Help Center](https://support.hoyoverse.com/hc/en-us/articles/50333893862297-What-is-Condensed-Resin).

This is an inference from inventory changes, not a confirmed reward-claim packet.
The capture helper batches changes, so multiple actions can be combined, and gains
cancelled by spending in the same batch cannot be detected. Bosses whose unique
drops are missing from the catalog, and rewards without unique drops, are not detected.
