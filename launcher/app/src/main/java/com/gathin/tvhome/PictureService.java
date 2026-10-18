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
    private static final String ID_SUMMARY = "android:id/summary";
    private static final String MENU_TITLE = "Display & Picture";
    private static final String ROW_MODE = "Picture Mode";
    private static final String ROW_BRIGHTNESS = "Brightness";

    private static final long STEP_MS = 450L;
    /** Moving between rows animates; keys that arrive mid-animation are dropped. */
    private static final long ROW_STEP_MS = 550L;
    private static final long WAIT_MS = 6_000L;
    private static final int MAX_MODES = 16;
    private static final int MAX_CHOICES = 12;
    private static final long FAST_STEP_MS = 140L;

    static final class Result {
        String mode;
        final List<String> modes = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        String error;
    }

    /** One row of a picture-mode recipe: a slider ({@code number}) or a list choice ({@code choice}). */
    static final class Setting {
        final String page, row, choice;
        final int number;

        private Setting(String page, String row, String choice, int number) {
            this.page = page;
            this.row = row;
            this.choice = choice;
            this.number = number;
        }

        static Setting choice(String page, String row, String value) {
            return new Setting(page, row, value, -1);
        }

        static Setting number(String page, String row, int value) {
            return new Setting(page, row, null, value);
        }

        @Override
        public String toString() {
            return row + " " + (choice != null ? choice : String.valueOf(number));
        }
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
        start(cb, r, () -> waitForRow(ROW_MODE, r, () -> findMode(targets, null, 0, r, () -> finish(r))));
    }

    /**
     * Switch to the first available mode in {@code targets}, then set each row of {@code recipe} on
     * that mode (Hisense stores these per picture mode). Rows that don't exist are skipped, not fatal.
     */
    void applyRecipe(List<String> targets, List<Setting> recipe, Done cb) {
        Result r = new Result();
        start(cb, r, () -> waitForRow(ROW_MODE, r,
                () -> findMode(targets, null, 0, r, () -> runRecipe(recipe, 0, null, r))));
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
        moveTo(row, presses, then, () -> fail(r, "row not found: " + row));
    }

    private void moveTo(String row, int presses, Runnable then, Runnable missing) {
        if (row.equals(valueOf(focusedRow(), ID_TITLE))) {
            then.run();
            return;
        }
        if (presses >= 24) {
            missing.run();
            return;
        }
        global(presses < 12 ? GLOBAL_ACTION_DPAD_DOWN : GLOBAL_ACTION_DPAD_UP);
        handler.postDelayed(() -> moveTo(row, presses + 1, then, missing), ROW_STEP_MS);
    }

    // ---- recipes ---------------------------------------------------------------------------------

    /** Settings run in order; consecutive ones on the same page share one visit to it. */
    private void runRecipe(List<Setting> recipe, int i, String page, Result r) {
        if (i >= recipe.size()) {
            if (page == null) finish(r);
            else leavePage(r, () -> finish(r));
            return;
        }
        Setting s = recipe.get(i);
        Runnable next = () -> runRecipe(recipe, i + 1, s.page, r);
        if (s.page.equals(page)) {
            applySetting(s, r, next);
        } else if (page != null) {
            leavePage(r, () -> runRecipe(recipe, i, null, r));
        } else {
            moveTo(s.page, 0, () -> {
                if (!pressOkOn(s.page)) {
                    fail(r, "lost track of the menu");
                    return;
                }
                later(() -> waitForDecor(s.page, SystemClock.uptimeMillis(), r, () -> applySetting(s, r, next)));
            }, () -> fail(r, "page not found: " + s.page));
        }
    }

    private void leavePage(Result r, Runnable then) {
        global(GLOBAL_ACTION_BACK);
        later(() -> waitForDecor(MENU_TITLE, SystemClock.uptimeMillis(), r, then));
    }

    private void applySetting(Setting s, Result r, Runnable next) {
        moveTo(s.row, 0, () -> {
            if (s.choice != null) pickChoice(s, null, 0, r, next);
            else setNumber(s, 0, r, next);
        }, () -> {
            r.skipped.add(s.row + " (not on this TV)");
            next.run();
        });
    }

    /** List rows (Gamma, Local Dimming…) cycle with Right; stop on the value or after a full lap. */
    private void pickChoice(Setting s, String start, int steps, Result r, Runnable next) {
        String cur = currentValue();
        if (cur == null) {
            r.skipped.add(s.row + " (couldn't read it)");
            next.run();
            return;
        }
        if (same(cur, s.choice)) {
            if (steps > 0) r.changed.add(s.row + " → " + cur);
            next.run();
            return;
        }
        String first = start == null ? cur : start;
        if ((steps > 0 && same(cur, first)) || steps >= MAX_CHOICES) {
            r.skipped.add(s.row + " (no \"" + s.choice + "\" option)");
            next.run();
            return;
        }
        global(GLOBAL_ACTION_DPAD_RIGHT);
        later(() -> pickChoice(s, first, steps + 1, r, next));
    }

    /** Sliders step by one per Left/Right; re-read and correct once in case a press was dropped. */
    private void setNumber(Setting s, int attempt, Result r, Runnable next) {
        int cur = parseInt(currentValue());
        if (cur < 0) {
            r.skipped.add(s.row + " (couldn't read it)");
            next.run();
            return;
        }
        int delta = s.number - cur;
        if (delta == 0) {
            if (attempt > 0) r.changed.add(s.row + " → " + cur);
            next.run();
            return;
        }
        if (attempt >= 3) {
            r.skipped.add(s.row + " (stuck at " + cur + ")");
            next.run();
            return;
        }
        press(delta > 0 ? GLOBAL_ACTION_DPAD_RIGHT : GLOBAL_ACTION_DPAD_LEFT, Math.abs(delta),
                () -> setNumber(s, attempt + 1, r, next));
    }

    private void press(int action, int times, Runnable then) {
        if (times <= 0) {
            later(then);
            return;
        }
        global(action);
        handler.postDelayed(() -> press(action, times - 1, then), FAST_STEP_MS);
    }

    /** The focused row's value: the inline chooser while focused, else its summary line. */
    private String currentValue() {
        AccessibilityNodeInfo row = focusedRow();
        String v = valueOf(row, ID_INDICATOR);
        return v != null ? v : valueOf(row, ID_SUMMARY);
    }

    private static int parseInt(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Walk the Picture Mode values with Right, recording every mode this content offers, until back
     * at the start (or on the first choice). Then step to the best available target.
     */
    private void findMode(List<String> targets, String start, int steps, Result r, Runnable then) {
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
            then.run();
            return;
        }
        boolean wrapped = steps > 0 && same(cur, first);
        if (!wrapped && steps < MAX_MODES) {
            global(GLOBAL_ACTION_DPAD_RIGHT);
            later(() -> findMode(targets, first, steps + 1, r, then));
            return;
        }
        if (!hasTargets) {
            then.run();
            return;
        }
        for (String t : targets) {
            if (containsLoose(r.modes, t)) {
                walkTo(t, 0, r, then);
                return;
            }
        }
        fail(r, "none of these modes are available here: " + targets);
    }

    private void walkTo(String target, int steps, Result r, Runnable then) {
        String cur = valueOf(focusedRow(), ID_INDICATOR);
        r.mode = cur;
        if (same(cur, target)) {
            then.run();
        } else if (steps >= MAX_MODES) {
            fail(r, "could not reach " + target);
        } else {
            global(GLOBAL_ACTION_DPAD_RIGHT);
            later(() -> walkTo(target, steps + 1, r, then));
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
