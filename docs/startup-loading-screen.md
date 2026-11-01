# Running-Lumine startup screen

An initial browser request to `/` accepting HTML receives a small, dependency-free
loading document before authentication or dashboard data preparation. After that
document paints, `startup/loader.js` fetches the real page using `X-Archive-Ready: true`.
That request goes through the normal security and MVC chain. The response is parsed
as a complete document, preserving script execution, forms, CSRF inputs, and login
redirects without requesting the dashboard a second time.

The loader has no minimum display duration. It preloads the returned page's
stylesheets, then hands off immediately. Failures and a 45-second timeout expose
retry and direct-navigation controls. `?direct=1` bypasses only the loading screen,
not authentication. Non-browser requests and other routes retain existing behavior.
English and German are supported, including the existing `genshin-language` cookie.

When the destination includes the enabled companion, the runner continues into
the new document as a temporary overlay. The page is usable immediately while
she shrinks/slides for 650 ms to the saved companion position, then plays the
numbered jog-to-idle motion. Login, hidden/paused companions and reduced motion
skip this flight. See [character-companion.md](character-companion.md).

The handoff marker now precedes the header bootstrap, stays visible for delayed
assets, and survives initial scrollbar/layout resize events. Resizing during
flight retargets it. Normal internal Home navigation uses the protected direct
route and restores the corner companion; its temporary parameters are removed
so refreshing Home still plays the full-screen loader and handoff.

The JavaFX desktop startup window uses the same image before Spring starts, and
stops its animation when startup finishes, fails, or the application closes.
This improves first-document presentation; it does not eliminate backend startup
work or allow an HTTP server to respond before it is listening.

## Animation source and runtime cost

Only the numbered motion collection is used:

`2_Female_Motions/3_Center_Bone_Movement_Motions/241_Female-Sprint_A-Forward(Requires_UpperBody2).vmd`

The packaged image is an 18-frame, 30-FPS transparent sprite sheet rendered from
Lumine's fitted PAC_MMD sprint. It retains the original arm swing, removes forward
travel, and uses the existing 0.94 IK-stride fit. The full sprite is approximately
167 KiB; the static reduced-motion frame is approximately 11 KiB. Its provenance,
frame dimensions, and source hash are recorded in
`src/main/resources/static/startup/lumine-sprint-source.json`.

Startup downloads no PMX, VMD, 3D engine, physics engine, or remote assets.
Reduced-motion users receive the static poster and no CSS animation.
The first document uses inline CSS, so it can paint without waiting for the
application stylesheet. The only public assets added are under `/startup/**`.

## Regenerating

The existing sprite is bundled, so building or running the app needs no Blender
installation or access to the source-model library. For a deliberate asset update:

1. Use a **copy** of Lumine's fitted Blender scene with the action
   `PAC_MMD_Sprint_A_Original_Arms`. Leave other motion tracks muted.
2. Keep the original numbered VMD at the recorded relative path beneath
   `.tmp/model-download-test/animations/`.
3. Run Blender in the background with that scene and
   `--python scripts/render-lumine-loader.py`.

The script selects a matching full-stride interval, settles clothing physics,
renders the frames, and invokes `scripts/pack-lumine-loader.cjs` for palette
compression. It writes generated frames under `.tmp/` and the three packaged
assets under `src/main/resources/static/startup/`; it does not save the Blender scene.

## Verification

- `StartupPageFilterTest`: the shell precedes controller/security work, normal
  requests still pass through, context paths work, and HEAD returns headers only.
- `StartupLoadingIntegrationTest`: the shell and assets are public while both the
  background document and direct fallback remain protected by authentication.
- `StartupAnimationAssetTest`: source selection, dimensions, transparency, and
  first-load size budgets.
- `node scripts/check-startup-loader.cjs`: isolated headless Chromium/Edge tests
  first paint while data is delayed, animation, one-document hand-off, script
  execution, CSRF retention, style readiness, login redirects, German retry,
  mobile layout, reduced motion, and absence of 3D-runtime requests. Set
  `STARTUP_TEST_BROWSER` to a Chromium executable if needed. Screenshots are saved
  under `.tmp/`.
