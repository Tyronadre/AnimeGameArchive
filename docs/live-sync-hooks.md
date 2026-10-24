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

This is an inference from inventory changes, not a confirmed reward-claim packet.
The capture helper batches changes, so multiple actions can be combined, and gains
cancelled by spending in the same batch cannot be detected. Bosses whose unique
drops are missing from the catalog, and rewards without unique drops, are not detected.
