# Virtual display — live capsule preview

Companion to `docs/virtual-display-P0.md` (frame pipeline). This page covers the
**visible layer**: the floating capsule that shows a virtual display's live
picture over the user's main screen, and the Settings › Virtual display page.

## Where each part lives

| Piece | File |
|---|---|
| `frames: StateFlow<Bitmap?>` on `MinisAgentDisplay` (preview ≤10 fps, short side ≤360 px, black frames publish `null`) | `app/display/MinisAgentDisplay.kt` |
| A11y overlay window mechanism (`TYPE_ACCESSIBILITY_OVERLAY`, ComposeView host, drag, close) | `app/capsule/VdOverlayHost.kt` |
| Capsule card: frame stream, 4-button top bar (back/mute/pause/close), tap-to-expand, "stale" indicator | `app/capsule/CapsulePreview.kt` (`MinisCapsule`, `CapsuleContent`) |
| Expanded PiP: full-screen dimmed overlay with the big frame, tap or × to collapse | `app/capsule/AgentPip.kt` (`VdAgentPip`) |
| Settings entry + management page (create/release displays, inline 500 ms poll preview, capture frame, launch app onto display) | `app/ui/settings/VirtualDisplayScreen.kt`, `SettingsScreen.kt`, `AppNavigation.kt` (`Routes.VIRTUAL_DISPLAY = "virtual_display"`) |
| Strings | `res/values`, `res/values-zh`, `res/values-ja` (`capsule_vd_*`, `vd_*`) |

## How the capsule works

1. **Lazy + frame-gated** (Mika lesson #117): `MinisCapsule` only exists
   while a virtual display does. `MinisAgentDisplayManager` fires a
   main-thread change listener on create/release; the capsule opens its
   overlay window when a display exists and removes it when the last one
   is released.
2. **No overlay permission needed**: the window is added by the
   `MinisAccessibilityService`'s own `WindowManager` (its accessibility
   token) with type `TYPE_ACCESSIBILITY_OVERLAY`. The a11y service must be
   enabled; without it the capsule simply does not appear (the in-app
   Settings preview still works).
3. **Frame source**: `MinisAgentDisplay.frames` — the existing ≤10 fps
   ImageReader pipeline already produces upright full frames; the
   backfill scales each one to short side ≤360 px before publishing
   (Mika `AgentDisplay._frames` / `previewWidth`). A black frame (the
   P0 black-frame guard) publishes `null`, so the capsule shows its
   "waiting for frame" placeholder instead of pure-black noise.
4. **Throttling**: `previewFrames()` (ported from Mika
   `capsule/CapsulePreview.kt`) conflates to ≤5 fps and skips frames
   identical to the last pushed one. The Settings-page pane instead polls
   `latestFrameBitmap()` every 500 ms (recycles the previous copy).
5. **Stale indicator**: `lastFrameAt()` (producer monotonic timestamp)
   older than 3 s turns the caption yellow ("the screen may be stuck") —
   covers the OWN_DISPLAY_GROUP doze and OEM-freeze failure modes.
6. **Tap to expand**: tapping the frame opens `VdAgentPip` — a second
   full-screen a11y overlay (dimmed background, ~55 % screen-width frame,
   tap anywhere or × to collapse).

## OEM re-bind tolerance

On ROMs that kill + rebind the a11y service (MIUI "此服务出现故障"),
`MinisCapsule` tears down on `onDestroy` and re-mounts on the next
`onServiceConnected` if a display still exists. If `addView` is refused
(token stale / system policy), `open` returns null and the capsule stays
hidden — **degraded mode: in-app preview only** (Settings › Virtual
display). The `AccessibilityRecoveryManager` breadcrumbs already on the
service (T-android-a11y-miui-service-failure) give the diagnostics.

## Buttons, current state

- **Back** — placeholder only (the agent flow, not the capsule, owns
  "back" semantics; the virtual display's own back-injection has known
  ROM gaps).
- **Mute** — placeholder (agent audio muting is a separate M-track).
- **Pause eye** — freezes/releases the frame stream in place.
- **×** — hides the capsule until the next display create/release.

## Licensing

The overlay-window *mechanism* is a trimmed port of Mika
`cap/phone/a11y/OverlayHost.kt` (Apache-2.0, see `THIRD_PARTY_LICENSES.md`).
The always-visible floating-capsule *interaction concept* is borrowed from
Mika's `capsule/` + `ui/AgentPip.kt` (≤5 fps card preview, tap-to-grow PiP);
the OpenMinis capsule/PiP code itself is original — no Mika UI code was
copied.
