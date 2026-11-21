/*
 * M1 Shizuku shell-host display service: binder interface for the privileged
 * user service that owns OpenMinis agent virtual displays (uid 2000 when
 * Shizuku was started via wireless debugging / USB debugging).
 *
 * Apache-2.0 attribution (Mika port):
 *
 * The subset of methods below and the `in Surface` parceling convention are
 * ported from wangzan101/Mika (Apache-2.0):
 *   - app/src/main/aidl/com/wangzan101/mika/shell/IMikaShell.aidl
 *     (createDisplay/releaseDisplay/injectTap/injectKey/runSystemCommand/
 *      identity/releaseAll, plus startAppInUser; trimmed to the display
 *     surface — no audio / clipboard / devtools / file policy).
 *
 * See THIRD_PARTY_LICENSES.md and docs/virtual-display-M1.md.
 */

package com.openminis.app.shizuku;

import android.os.Bundle;
import android.view.Surface;

/**
 * Implemented by {@code MinisShellHostService}, which Shizuku instantiates
 * (via reflection, in Shizuku's privileged app_process helper) and which the
 * Minis app reaches through {@code MinisShellClient}.
 *
 * <p>The {@code surface} parameter of {@code createDisplay} is a parceled
 * {@link Surface} — the app-side ImageReader surface — exactly as Mika does
 * in IMikaShell. The shell side hands it straight to
 * {@code DisplayManager.createVirtualDisplay}, so the app process must keep
 * the ImageReader alive for the whole lifetime of the display; frame pixels
 * land in the APP process, never on any physical screen.
 */
interface IMinisShell {
    /**
     * Create a PUBLIC virtual display owned by this service (shell uid).
     *
     * @param surface  parceled app-side ImageReader surface (kept open by caller)
     * @return the new displayId, or -1 when creation failed even after the
     *         trusted → untrusted downgrade retry
     */
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;

    /** Release one display created by {@link #createDisplay}. Idempotent. */
    void releaseDisplay(int displayId) = 2;

    /**
     * Launch an app component onto a display, in a (possibly secondary) user.
     *
     * @param component  "pkg/activity" or "pkg/.Activity"; a bare "pkg" means
     *                   "use the user's launcher intent". Cross-user
     *                   (secondary / dual-app) launches go through
     *                   ILauncherApps first, then IActivityTaskManager,
     *                   then `am start --user N --display D -n ...`.
     * @param userId     target user (0 = primary; 999-style = dual-app copy)
     * @return 0 = started · 1 = unknown/bad component · 2 = display is not
     *         ours (refused, e.g. the main display) · 3 = user not in the
     *         whitelist · 4 = all launch paths failed
     */
    int startAppInUser(int displayId, String component, int userId) = 3;

    /** Tap (x,y) on the display; falls back to `input -d <id> tap`. */
    boolean injectTap(int displayId, int x, int y) = 4;

    /**
     * Inject a key event on the display; falls back to
     * `input -d <id> keyevent <code>`. Device-level keys (power, volume,
     * home, ...) are refused by the host.
     */
    boolean injectKey(int displayId, int keyCode) = 5;

    /**
     * Type text on the display. ASCII goes through virtual-keyboard key
     * events (targeted at the launched app's uid on API 33+); characters
     * without a key mapping fall back to `input -d <id> text` (ASCII only —
     * CJK/Unicode text input is an M2 clipboard-paste path).
     */
    boolean injectText(int displayId, String text) = 6;

    /**
     * Run one shell command with shell privilege, optionally scoped to a
     * display. Returns a Bundle with `exitCode` (int) and `output` (String);
     * when {@code displayId >= 0} the command sees `MINIS_DISPLAY_ID` in its
     * environment. The display must be one this service created.
     */
    Bundle runSystemCommand(int displayId, String command) = 7;

    /** {@code [uid, pid]} of the service process — uid 2000 when Shizuku
     *  runs in shell mode; the app uses it to verify it talks to the right
     *  helper (cf. IMikaShell.identity). */
    int[] identity() = 8;

    /** Release every display and stop all keep-awake loops. */
    void releaseAll() = 9;
}
