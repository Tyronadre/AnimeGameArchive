# Lumine companion

The shared site header includes a tiny inline continuity bootstrap before page
content can paint, followed by the deferred companion script. On a fresh page
without a handoff or saved frame, it schedules the widget after window load
during browser idle time (with a
2.5-second deadline, or a 500-ms fallback). The companion stylesheet and images
are loaded only when enabled. Login and the initial startup document do not use
this widget. It follows the existing theme variables and includes English and
German controls.

Drag Lumine with the mouse (or a touch/pen pointer) to place her inside the
viewport. A 5-pixel threshold separates dragging from clicking. Pointer capture
keeps a drag active outside her sprite; Escape cancels it. Click for controls.
Pause/resume, left/right placement, a normalized free position and hiding are
stored locally under `genshin-archive.companion.v1`. Normalized coordinates
preserve placement across different window sizes. Safe area bounds keep her
visible, and the menu chooses a position inside the viewport. The menu's
left/right controls clear the free position and return her to a corner.
The Lumine button in the header restores a hidden companion. Escape closes the menu and returns focus.
Reduced motion uses a static poster; paused preferences also load only a poster
on subsequent pages. Hidden browser tabs suspend animation. This is a rendering
of the fitted 3D model, rather than a WebGL scene on every page.

## Full-screen handoff

When the startup request returns an authenticated page with companion controls,
the loader captures the runner's viewport rectangle and animation phase. It
includes a temporary, non-interactive copy in the new server document. The page
becomes usable immediately, its backdrop fades out, and the sprite shrinks/slides
for 650 ms toward the companion's saved location. The compact-height loader
layout is supported.

The companion initializes immediately for this handoff instead of waiting for
browser idle time. On arrival it plays the authored stop and returns to idle.
The temporary overlay is removed, leaving one companion. Login redirects,
hidden/paused companions and reduced motion bypass the flight. A CSS expiry
prevents a leftover overlay from remaining visible if companion assets fail.
The marker precedes the header script, so warm cached scripts can find it. A
14-second script fallback and 15-second CSS fallback allow slow assets to finish;
the former 2.5-second expiry could hide the flight before it ever started.
Initial layout/scrollbar resize notifications preserve a pending handoff. A
resize during flight retargets it from its current position. Hiding the tab,
pausing or changing motion preferences can still finish the flight early.

## Continuity between pages

Normal server navigation remains intact: each page keeps its own scripts, forms,
authentication and lifecycle. Before leaving, the companion records a small PNG
of its current sprite frame, position, pose and animation phase in per-tab
session storage. The incoming header displays that frame immediately, without
another image download, while the live sprite initializes. It then resumes the
pose/phase at the saved position and removes the temporary frame. Paused and
reduced-motion companions also retain a static frame; hidden companions remain
hidden. Saved navigation frames expire after 15 seconds and are consumed once.

The saved frame is painted by the inline header bootstrap before page content,
including when the companion script, stylesheet or sprites are uncached and slow.
This uses the normal page lifecycle and works with reduced motion as well.

An internal Home click uses the existing protected `?direct=1` route with a
temporary `companionNavigation=1` marker, so it does not replay the full-screen
intro during ordinary navigation. The incoming bootstrap removes both temporary
parameters from history. A fresh visit or reload of the normal Home URL still
shows the full-screen loader and its corner transition. Asset and Home URLs
respect deployment context paths such as `/archive/`.

## Authored animation assets

- Idle: numbered `2_Female_Motions/2_Idle_Motions/290L-290L_Female-Idle_L(Requires_UpperBody2).vmd`.
  22 transparent 256×320 frames at 10 FPS, approximately 143 KiB total; poster 12 KiB.
- The idle file is a partial motion. Its missing pose channels come from the
  **final stopped pose** of numbered `202_Female-Walk-Forward_to_Stop` in the
  center-movement collection. Accumulated forward travel is removed from the
  center and foot IK controllers before applying the authored idle. No arbitrary
  arm offsets are introduced.
- Running uses the existing 18-frame, 30-FPS startup asset and the browser cache.
  Its original authored arm swing and fitted stride remain unchanged.
- Start: numbered female `4_Master_Root_Movement_Motions/290L-220_Female-Idle_L_to_Jog-Forward(Requires_UpperBody2).vmd`.
  22 frames at 10 FPS, 2.1 seconds, approximately 205 KiB.
- Stop: numbered female `4_Master_Root_Movement_Motions/220-end_Female-Jog-Forward_to_Idle(Requires_UpperBody2).vmd`.
  26 frames at 10 FPS, 2.5 seconds, approximately 238 KiB. The pack has jog start/stop
  transitions rather than a dedicated female sprint start/stop. These authored
  one-shots accompany the existing sprint loop. Master travel/dummy keys are
  removed, the 0.94 foot-IK fit is retained, and the render camera moves between
  idle and sprint views. A short crossfade blends adjacent authored clips.
- Provenance is bundled beside each sprite in `*-source.json`. Source VMDs and the
  user's live Blender scene are unchanged. Builds need neither Blender nor the
  external model library.

To regenerate the idle, run Blender on a copy of the fitted scene:

```powershell
& 'C:/Program Files/Blender Foundation/Blender 5.2/blender.exe' --background .tmp/loader-lumine-source.blend --python scripts/render-lumine-companion.py
```

The script requires the installed MMD Tools extension and numbered source files
under `.tmp/model-download-test/animations`. It warms up and bakes the existing
clothing physics, renders only into the project, then calls the shared PNG packer
with `--companion-idle`. It never saves the input scene.

Append `-- --clip start` or `-- --clip stop` to the Blender command to regenerate
the authored one-shots. Transition poses receive 66 frames of physics warm-up.
The packer records their source ranges, hashes, travel removal and camera fit.

## Activity integration

The companion exposes activity tokens without wrapping global `fetch`. A page
can signal meaningful work and release its token in `finally`:

```javascript
const companionActivity = window.archiveCompanion?.begin();
try {
    await performWork();
} finally {
    window.archiveCompanion?.end(companionActivity);
}
```

Overlapping work keeps the companion running until all tokens are released.
Work completed within 650 ms does not change the idle. V2 damage calculations,
dashboard plan refreshes and ordinary same-origin navigation are integrated.
Routine activity polling does not trigger movement. Navigation feedback has a
15-second fallback and is reset on pageshow, including browser back/forward cache.
The widget does not delay or replace navigation or the original error feedback.

The normal sequence is idle → authored start → running loop → authored stop →
idle. One-shots play once and hold their last frame, then advance state using the
animation-end event. If work ends during the start, it finishes and proceeds to
the stop. If new work arrives during the stop, the next start follows it. Missing
optional transition images fall back to the corresponding loop/rest state.

## Verification

`node scripts/check-character-companion.cjs` runs isolated headless Edge/Chromium
checks for animation, overlapping activity, menu keyboard focus, persistence
across pages, hiding without downloads, restoring, reduced motion, German text,
mobile bounds, invalid saved preferences, actual pointer dragging, saved free
positions, Escape cancellation, dock reset, viewport resizing and deployment
under `/archive`. It also checks the authored start/stop one-shots.
Screenshots are saved to `.tmp/companion-{desktop,mobile}-preview.png`.

`StartupLoadingIntegrationTest` verifies the authenticated dashboard renders the
shared-header controls and serves companion assets through normal Spring security.
The startup tests also cover the lightweight loading document and its hand-off.
`check-startup-loader.cjs` also checks the flight to a saved free position, the
authored stop on arrival, cleanup, actual reloads, delayed assets, initial layout
events, resizing during flight and hidden/reduced-motion bypasses. The companion
check exercises full page navigation with uncached slow scripts and assets,
verifies the saved frame appears before the companion script runs and remains
visible, and checks Home navigation and paused continuity.

Walking between docks, a separate thinking animation and live 3D inspection are
tracked in [character-experience-roadmap.md](character-experience-roadmap.md).
