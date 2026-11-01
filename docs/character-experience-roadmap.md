# Character experience roadmap

Agreed direction: characters take part in the archive, with quiet motion during
everyday use and more expressive stages when viewing or comparing characters.
Implement the following milestones in order; keep this file current as each ships.

## Shared rules

- Preserve the archive's existing design tokens, light/dark themes and readable data.
- The first page must remain lightweight. Load character presentation after the
  page is usable; never require a model or renderer to navigate or calculate.
- Use motions **only from the numbered folders** in the local animation library.
  Do not use the loose demo motions or the unnumbered reaction collection.
- Preserve the fitted Lumine sprint, original arm swing and clothing adjustments.
- Respect reduced motion, suspend offscreen/background animation, and provide
  persistent pause/hide controls. No essential information relies on animation.
- Missing models get a portrait stage with the same framing, elemental lighting
  and interactions. Model availability must not limit archive functionality.
- Reuse one renderer per active stage, rather than a WebGL canvas per catalog card.

## Milestones

1. **Persistent companion — initial version implemented.** Lumine in a screen corner with authored
   idle and running states, a small menu, a saved side, pause and hide/restore.
   React to genuine calculations and navigation. Start with renders of the fitted
   3D character so ordinary pages do not need a 3D engine. Add an authored walk
   between docks and a thinking state after their source poses are validated.
2. **Character page entrances — implemented.** A silhouette resolves into the character
   stage, the camera settles, then the name and statistics appear. Use elemental
   light and a portrait presentation whenever a compatible model is unavailable.
3. **Animated team lineup — queued.** Four shared stage positions; a character
   enters the selected slot and the active character receives a camera/lighting
   emphasis. Keep team editing fully usable with portraits and keyboard controls.
4. **Elemental page transitions — queued.** Brief Hydro ripples, Anemo particles,
   Electro arcs or Geo fragments, with instantaneous navigation under reduced
   motion. Transitions must never delay a completed request.
5. **Interactive collection — queued.** Cards lift subtly on hover/focus; an
   expanded showcase displays idle motion and equipment while other cards recede.
   Load only the opened showcase, including on touch devices.
6. **Equipment comparison stages — queued.** Keep the character visible beside
   comparison statistics; selected equipment affects stage light/aura. Mark a
   completed goal with constellation effects using available motions only.
7. **Farming guide — queued.** The companion follows the selected activity, turns
   toward a task and marks meaningful completed goals with constellation light.
8. **Inspection mode — queued.** Orbit, zoom, lighting/background controls and an
   animation picker limited to compatible, reviewed numbered motions.
9. **Personal home scene — queued.** Saved character, elemental backdrop and stage
   style, with a lightweight first paint and deferred 3D enhancement.

## Presentation language

Showcases use soft spotlights, subtle elemental particles and translucent panels.
Daily pages retain readable cards and peripheral movement. Trails and luminous
borders belong to deliberate selections, transitions and completed goals.


## Existing foundation

The running-Lumine startup screen is implemented separately. See
[startup-loading-screen.md](startup-loading-screen.md) for assets, provenance and
verification. The old animation shortlist includes candidates outside the allowed
numbered packs and is **not** an implementation allowlist.

## Companion delivery

The initial companion is included by the shared authenticated-site header. It
loads after the window finishes loading and the browser has idle time. It has
the numbered female idle, the existing fitted sprint for work lasting over
650 ms, English/German controls, saved visibility/pause/side, keyboard access,
static reduced-motion assets and background suspension. Calculations and plan
refreshes release their activity tokens even on errors or cancellation.

Only the visible pose is downloaded; hiding the companion prevents its style and
image downloads on the next page. No 3D runtime is added to ordinary pages. Walking
between positions and a separate thinking pose remain companion follow-ups after
visual fitting; the current side switch is immediate. See
[character-companion.md](character-companion.md) for maintenance and verification.

The companion now supports dragging with saved free placement, viewport/menu
bounds, Escape cancellation and a full-screen startup-to-companion handoff. The
numbered idle-to-jog and jog-to-idle motions handle starting/stopping around the
existing sprint, including stopping on arrival. The pack has no dedicated female
sprint-to-idle clip; the authored jog transitions are used explicitly.

Reload reliability and page navigation continuity are implemented. Slow assets
and initial resize notifications preserve the full-screen flight; later resizes
retarget it. A saved sprite frame carries Lumine between full server pages until
the live companion is ready. Internal Home navigation skips repeating the intro,
while a fresh visit or reload retains it.

Verification: the startup/companion Spring suite passes (11 tests), and both
headless browser checks pass. Desktop and mobile screenshots are recorded by the
companion browser check. The running desktop application can be restarted after
rebuilding to pick up the new packaged resources.

## Character entrance delivery

Character detail pages now have elemental stages, silhouette reveals, camera
settling and a staggered name/facts entrance. After the page is usable, compatible
desktop pages load one real PMX model with an authored numbered-pack idle. Artwork
remains visible while assets load. The same stage frames portrait fallbacks.
Phones, reduced-motion and data-saver users start with artwork and can choose 3D.
Pause and artwork preferences persist; offscreen/background rendering stops.

The library maps 47 character identities, choosing the base outfit when present
and excluding props. Kinich currently falls back because `hair_s.bmp` is missing.
Lumine's fitted mesh is included with her skirt clearance preserved. Other models
use the configured local folder; absent files or incompatible rigs fall back to
artwork without affecting character planning. See
[character-page-entrances.md](character-page-entrances.md) for configuration,
provenance, regeneration and verification.

