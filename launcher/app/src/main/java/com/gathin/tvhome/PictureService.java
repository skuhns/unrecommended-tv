package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hisense keeps picture mode and backlight in a vendor service no app can call, and its settings
 * provider is not exported. What does work: open its picture menu (android.settings.DISPLAY_SETTINGS),
 * read the rows through accessibility, and press D-pad keys with the Android 13+ DPAD global actions.
 * The caller opens the menu; this service drives it (and closes it again after a mode change).
 */
public class PictureService extends AccessibilityService {

    static final String PKG = "com.vt.customsettings";
    /** Value rows use the platform title id; rows that open a submenu use Hisense's own. */
    private static final String ID_TITLE = "android:id/title";
    private static final String ID_TITLE_HISENSE = PKG + ":id/title";
    private static final String ID_INDICATOR = PKG + ":id/indicator";
    private static final String ID_DECOR_TITLE = PKG + ":id/decor_title";
    private static final String MENU_TITLE = "Display & Picture";
    private static final String ROW_MODE = "Picture Mode";
    private static final String ROW_BRIGHTNESS = "Brightness";

    private static final long STEP_MS = 450L;
    /** Moving between rows animates; keys that arrive mid-animation are dropped. */
    private static final long ROW_STEP_MS = 550L;
    private static final long WAIT_MS = 6_000L;
    private static final int MAX_MODES = 16;

    static final class Result {
        String mode;
        final List<String> modes = new ArrayList<>();
        String error;
    }

    interface Done {
        void done(Result r);
    }

    private static PictureService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean busy;

    static PictureService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    boolean isBusy() {
        return busy;
    }

    // ---- public operations (call after starting DISPLAY_SETTINGS) --------------------------------

    /** Switch to the first of {@code targets} this content offers (names compared loosely). Empty targets: just read. */
    void setMode(List<String> targets, Done cb) {
        Result r = new Result();
        start(cb, r, () -> waitForRow(ROW_MODE, r, () -> findMode(targets, null, 0, r)));
    }

    /** Open the menu's Brightness page with the Brightness slider selected, and leave it open. */
    void openBrightness(Done cb) {
        Result r = new Result();
        start(cb, r, () -> waitForRow(ROW_MODE, r, () -> moveTo(ROW_BRIGHTNESS, 0, r, () -> {
            if (!pressOkOn(ROW_BRIGHTNESS)) {
                fail(r, "lost track of the menu");
                return;
            }
            later(() -> waitForDecor(ROW_BRIGHTNESS, SystemClock.uptimeMillis(), r,
                    () -> moveTo(ROW_BRIGHTNESS, 0, r, () -> release(r))));
        })));
    }

    // ---- steps -----------------------------------------------------------------------------------

    private Done pending;

    private void start(Done cb, Result r, Runnable first) {
        if (busy) {
            r.error = "busy";
            cb.done(r);
            return;
        }
        busy = true;
        pending = cb;
        later(first);
    }

    private void finish(Result r) {
        closeMenu(0, () -> {
            busy = false;
            Done cb = pending;
            pending = null;
            if (cb != null) cb.done(r);
        });
    }

    /** Done, but leave the menu open for the viewer. */
    private void release(Result r) {
        busy = false;
        Done cb = pending;
        pending = null;
        if (cb != null) cb.done(r);
    }

    private void fail(Result r, String why) {
        r.error = why;
        finish(r);
    }

    /** Wait for the picture menu with {@code row} focused (moving up to it if another row has focus). */
    private void waitForRow(String row, Result r, Runnable then) {
        long t0 = SystemClock.uptimeMillis();
        Runnable check = new Runnable() {
            @Override
            public void run() {
                AccessibilityNodeInfo root = menuRoot();
                if (root != null && MENU_TITLE.equals(textOf(root, ID_DECOR_TITLE))) {
                    moveTo(row, 0, r, then);
                } else if (SystemClock.uptimeMillis() - t0 > WAIT_MS) {
                    fail(r, "picture menu did not open");
                } else {
                    handler.postDelayed(this, 200);
                }
            }
        };
        check.run();
    }

    private void waitForDecor(String decor, long t0, Result r, Runnable then) {
        AccessibilityNodeInfo root = menuRoot();
        if (root != null && decor.equals(textOf(root, ID_DECOR_TITLE))) {
            then.run();
        } else if (SystemClock.uptimeMillis() - t0 > WAIT_MS) {
            fail(r, decor + " menu did not open");
        } else {
            handler.postDelayed(() -> waitForDecor(decor, t0, r, then), 200);
        }
    }

    /** Press Down (or Up, after a full pass) until the focused row's title is {@code row}. */
    private void moveTo(String row, int presses, Result r, Runnable then) {
        if (row.equals(valueOf(focusedRow(), ID_TITLE))) {
            then.run();
            return;
        }
        if (presses >= 20) {
            fail(r, "row not found: " + row);
            return;
        }
        global(presses < 10 ? GLOBAL_ACTION_DPAD_DOWN : GLOBAL_ACTION_DPAD_UP);
        handler.postDelayed(() -> moveTo(row, presses + 1, r, then), ROW_STEP_MS);
    }

    /**
     * Walk the Picture Mode values with Right, recording every mode this content offers, until back
     * at the start (or on the first choice). Then step to the best available target.
     */
    private void findMode(List<String> targets, String start, int steps, Result r) {
        String cur = valueOf(focusedRow(), ID_INDICATOR);
        if (cur == null) {
            fail(r, "could not read picture mode");
            return;
        }
        String first = start == null ? cur : start;
        if (!containsLoose(r.modes, cur)) r.modes.add(cur);
        r.mode = cur;
        boolean hasTargets = targets != null && !targets.isEmpty();
        if (hasTargets && same(cur, targets.get(0))) {
            finish(r);
            return;
        }
        boolean wrapped = steps > 0 && same(cur, first);
        if (!wrapped && steps < MAX_MODES) {
            global(GLOBAL_ACTION_DPAD_RIGHT);
            later(() -> findMode(targets, first, steps + 1, r));
            return;
        }
        if (!hasTargets) {
            finish(r);
            return;
        }
        for (String t : targets) {
            if (containsLoose(r.modes, t)) {
                walkTo(t, 0, r);
                return;
            }
        }
        fail(r, "none of these modes are available here: " + targets);
    }

    private void walkTo(String target, int steps, Result r) {
        String cur = valueOf(focusedRow(), ID_INDICATOR);
        r.mode = cur;
        if (same(cur, target)) {
            finish(r);
        } else if (steps >= MAX_MODES) {
            fail(r, "could not reach " + target);
        } else {
            global(GLOBAL_ACTION_DPAD_RIGHT);
            later(() -> walkTo(target, steps + 1, r));
        }
    }

    /**
     * OK is the only key that can do damage here (the menu has a Reset row), so re-read the focused
     * row right before pressing it and refuse unless it is exactly the one we mean.
     */
    private boolean pressOkOn(String row) {
        if (!row.equals(valueOf(focusedRow(), ID_TITLE))) return false;
        global(GLOBAL_ACTION_DPAD_CENTER);
        return true;
    }

    private void closeMenu(int tries, Runnable then) {
        if (menuRoot() == null || tries >= 4) {
            then.run();
            return;
        }
        global(GLOBAL_ACTION_BACK);
        handler.postDelayed(() -> closeMenu(tries + 1, then), 350);
    }

    // ---- node helpers ----------------------------------------------------------------------------

    private void later(Runnable r) {
        handler.postDelayed(r, STEP_MS);
    }

    private void global(int action) {
        performGlobalAction(action);
    }

    /**
     * The menu's own window. Hisense also pops up a tooltip window ("Press OK to zoom out") from the
     * same package, so pick the window that holds the menu title.
     */
    private AccessibilityNodeInfo menuRoot() {
        AccessibilityNodeInfo any = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || !PKG.contentEquals(String.valueOf(root.getPackageName()))) continue;
            if (textOf(root, ID_DECOR_TITLE) != null) return root;
            if (any == null) any = root;
        }
        return any;
    }

    /** The highlighted row. Walk the tree: findFocus() comes back empty while the tooltip popup is up. */
    private AccessibilityNodeInfo focusedRow() {
        AccessibilityNodeInfo root = menuRoot();
        return root != null ? findFocused(root, 0) : null;
    }

    private static AccessibilityNodeInfo findFocused(AccessibilityNodeInfo node, int depth) {
        node.refresh();   // cached nodes can still claim an old row has focus
        if (node.isFocused()) return node;
        if (depth > 30) return null;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo hit = findFocused(child, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private static String valueOf(AccessibilityNodeInfo node, String viewId) {
        if (node == null) return null;
        String v = textOf(node, viewId);
        return v == null && viewId.equals(ID_TITLE) ? textOf(node, ID_TITLE_HISENSE) : v;
    }

    private static String textOf(AccessibilityNodeInfo node, String viewId) {
        List<AccessibilityNodeInfo> found = node.findAccessibilityNodeInfosByViewId(viewId);
        if (found == null || found.isEmpty() || found.get(0).getText() == null) return null;
        return found.get(0).getText().toString();
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    static boolean same(String a, String b) {
        return norm(a).equals(norm(b));
    }

    private static boolean containsLoose(List<String> list, String s) {
        for (String x : list) if (same(x, s)) return true;
        return false;
    }
}
