# Artifact damage comparison (V2)

The Artifact comparison tab in `/damage-calculator-v2/nefer` compares individual
inventory replacements with the currently displayed build. It defaults to the
sum of Nefer's three Shades hits. Team equipment stays fixed except for one slot
on the selected character. All damage comes from the existing V2 evaluator.
The original artifact optimizer and its quality scores are unchanged.

Manual calculator values are retained as offsets from the imported equipment.
Newly gained set effects use the V2 defaults shown in the result details. Results
are damage for the selected hits, not rotation DPS. Unsupported active effects
are reported instead of silently producing complete-looking rankings.

Ordinary unfinished five-star pieces receive 800 deterministic upgrade samples.
The table reports the mean damage, 10th–90th percentiles, and the probability of
beating today's equipment. When the equipped reference can be projected, it also
compares the two independent final distributions. Special upgrade metadata is
not simulated. No inventory changes are made.

## Runtime and diagnostics

The authenticated API under `/api/damage-formulas/v2/{key}/artifact-comparisons`
supports POST to start, GET `/{runId}` to poll results, and DELETE `/{runId}` to
cancel. GET accepts `slot`, `sort` (`current`, `potential`, `chance`), `offset`,
and `limit` (1–100). POST takes `calculation` (the existing V2 request),
`formulaIds`, and the V2 document's `contextFingerprint`.

There are two worker threads, 16 queued jobs, and one active job per user. Results
expire after 30 minutes; older completed runs can be evicted earlier to bound
memory (two previous runs per user, 48 retained globally). They do not survive an
application restart. The current browser tab can reconnect to its run on reload.

ETA uses an exponentially weighted mean (25% new measurement) of measured time
per evaluation, separately for current comparisons and projections. Three
batches and two seconds of measurements are required. Current comparisons show
a phase estimate; projections show an estimate for the rest of the run. Queue
time is separate. Status updates once per second; disconnected clients stop
displaying an apparently live countdown. Cancellation is checked at least every
32 projection samples and between candidates.

Every log event contains `run=<UUID>`. Copy that ID from Run details to locate a
run in the normal application log. INFO records phase changes, a progress summary
at most every 30 seconds, and terminal duration/count/throughput totals. WARN
aggregates unsupported candidates. ERROR includes the exception plus candidate,
seed and sample context. DEBUG adds configuration, manual offsets, preparation
reuse, and one result summary per candidate; samples are never logged individually.

Enable detailed diagnostics through Spring configuration:

```properties
logging.level.de.tyro.genshinapp.service.damage.v2.comparison=DEBUG
```

Leave the default INFO level for normal use. No separate log destination or
unbounded per-sample logging is introduced.

## Checks

Use Java 21 with Gradle. Run the comparison, upgrade simulator, V2 formula, and
artifact optimizer tests. `node scripts/check-artifact-comparison-v2.cjs` runs a
local headless-browser fixture covering progress, ETA, partial results, connection
recovery, cancellation, and resuming after refresh. It stores a screenshot in
`.tmp/artifact-comparison-v2.png`.
