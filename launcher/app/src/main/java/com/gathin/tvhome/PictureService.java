package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.graphics.PixelFormat;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * TVs keep picture mode and backlight in vendor services no app can call. What does work: open the
 * TV's own picture menu, read its rows through accessibility, and press D-pad keys with the Android
 * 13+ DPAD global actions. Which menu, rows and on-screen ids come from the TV profile (PictureConfig,
 * picture.json). The caller opens the menu; this service drives it (and closes it after a mode change).
 */
public class PictureService extends AccessibilityService {

    /** The TV's picture menu, picture modes and panel button (from the TV profile). */
    private PictureConfig cfg;

    /** Poll the menu this often while waiting for it to react to a key. */
    private static final long POLL_MS = 60L;
    /** Give up waiting for a visible change after this long (a press that changes nothing). */
    private static final long SETTLE_MAX_MS = 700L;
    /** Gap between the Right presses of a mode jump. */
    private static final long MODE_KEY_MS = 180L;
    private static final long WAIT_MS = 6_000L;
    private static final int MAX_MODES = 16;
    private static final int MAX_CHOICES = 12;
    private static final long FAST_STEP_MS = 90L;

    static final class Result {
        String mode;
        final List<String> modes = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        boolean recipeRan;
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

    /**
     * A remote button opens the Picture panel over any app. Remote keys never reach accessibility
     * services on such TVs (the system handles them first), so this listens for the vendor's
     * key-press broadcast instead (Hisense: com.hisense.INTERCEPT_BUTTON_OPERATING, one per key-down,
     * with the keycode). Pick a button that does nothing else (Hisense: the TV/Guide button, 172).
     */
    private final BroadcastReceiver keys = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i.getIntExtra(cfg.panelExtra, -1) == cfg.panelKeycode) toggleOverlay();
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        cfg = PictureConfig.get(this);
        if (cfg.enabled() && !cfg.panelBroadcast.isEmpty()) {
            IntentFilter f = new IntentFilter(cfg.panelBroadcast);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(keys, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(keys, f);
            keysRegistered = true;
        }
    }

    // ---- the panel as an overlay over whatever is playing ------------------------------------------

    private View overlay;
    private boolean keysRegistered;

    private void toggleOverlay() {
        if (overlay != null) hideOverlay();
        else if (!busy) showOverlay();
    }

    private void showOverlay() {
        ContextThemeWrapper themed = new ContextThemeWrapper(this, R.style.Theme_TvHome_Panel);
        overlay = LayoutInflater.from(themed).inflate(R.layout.activity_picture, null);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT);
        lp.dimAmount = 0.35f;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(overlay, lp);
        new PicturePanel(themed, overlay, new PicturePanel.Host() {
            @Override
            public void start(Intent intent) {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }

            @Override
            public void beforeMenu() {
                hideOverlay();   // the TV's menu needs the screen and the remote
            }

            @Override
            public void afterLook(String summary) {
                Toast.makeText(PictureService.this, summary, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void close() {
                hideOverlay();
            }
        });
    }

    private void hideOverlay() {
        if (overlay == null) return;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlay);
        overlay = null;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        if (keysRegistered) unregisterReceiver(keys);
        keysRegistered = false;
        hideOverlay();
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

    /**
     * Switch to the first of {@code targets} the playing content offers, then, if {@code recipeFor}
     * returns settings for the mode it landed on, apply them to that mode.
     */
    void applyLook(List<String> targets, Function<String, List<Setting>> recipeFor, Done cb) {
        Result r = new Result();
        start(cb, r, () -> waitForRow(cfg.modeRow, r, () -> chooseMode(targets, r, () -> {
            List<Setting> recipe = r.mode != null ? recipeFor.apply(r.mode) : null;
            if (recipe == null) {
                finish(r);
                return;
            }
            r.recipeRan = true;
            runRecipe(recipe, 0, null, r);
        })));
    }

    /** Open the menu's Brightness page with the Brightness slider selected, and leave it open. */
    void openBrightness(Done cb) {
        Result r = new Result();
        start(cb, r, () -> waitForRow(cfg.modeRow, r, () -> moveTo(cfg.brightnessRow, 0, r, () -> {
            if (!pressOkOn(cfg.brightnessRow)) {
                fail(r, "lost track of the menu");
                return;
            }
            waitForDecor(cfg.brightnessRow, SystemClock.uptimeMillis(), r,
                    () -> moveTo(cfg.brightnessRow, 0, r, () -> release(r)));
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
        handler.postDelayed(first, POLL_MS);
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
                if (root != null && cfg.menuTitle.equals(textOf(root, cfg.pageTitleId))) {
                    moveTo(row, 0, r, then);
                } else if (SystemClock.uptimeMillis() - t0 > WAIT_MS) {
                    fail(r, "picture menu did not open");
                } else {
                    handler.postDelayed(this, POLL_MS);
                }
            }
        };
        check.run();
    }

    private void waitForDecor(String decor, long t0, Result r, Runnable then) {
        AccessibilityNodeInfo root = menuRoot();
        if (root != null && decor.equals(textOf(root, cfg.pageTitleId))) {
            then.run();
        } else if (SystemClock.uptimeMillis() - t0 > WAIT_MS) {
            fail(r, decor + " menu did not open");
        } else {
            handler.postDelayed(() -> waitForDecor(decor, t0, r, then), POLL_MS);
        }
    }

    /** Press Down (or Up, after a full pass) until the focused row's title is {@code row}. */
    private void moveTo(String row, int presses, Result r, Runnable then) {
        moveTo(row, presses, then, () -> fail(r, "row not found: " + row));
    }

    private void moveTo(String row, int presses, Runnable then, Runnable missing) {
        String here = focusedTitle();
        if (row.equals(here)) {
            then.run();
            return;
        }
        if (presses >= 24) {
            missing.run();
            return;
        }
        global(presses < 12 ? GLOBAL_ACTION_DPAD_DOWN : GLOBAL_ACTION_DPAD_UP);
        whenChanged(this::focusedTitle, here, () -> moveTo(row, presses + 1, then, missing));
    }

    private String focusedTitle() {
        return titleOf(focusedRow());
    }

    /**
     * Continue as soon as {@code read} differs from {@code before} (the menu caught up with a key),
     * or after SETTLE_MAX_MS if nothing visibly changed (e.g. Down on the last row).
     */
    private void whenChanged(Supplier<String> read, String before, Runnable then) {
        long t0 = SystemClock.uptimeMillis();
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                String now = read.get();
                boolean moved = now != null && !now.equals(before);
                if (moved || SystemClock.uptimeMillis() - t0 > SETTLE_MAX_MS) then.run();
                else handler.postDelayed(this, POLL_MS);
            }
        };
        handler.postDelayed(poll, POLL_MS);
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
                waitForDecor(s.page, SystemClock.uptimeMillis(), r, () -> applySetting(s, r, next));
            }, () -> fail(r, "page not found: " + s.page));
        }
    }

    private void leavePage(Result r, Runnable then) {
        global(GLOBAL_ACTION_BACK);
        waitForDecor(cfg.menuTitle, SystemClock.uptimeMillis(), r, then);
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
        whenChanged(this::currentValue, cur, () -> pickChoice(s, first, steps + 1, r, next));
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
        press(action, times, FAST_STEP_MS, then);
    }

    private void press(int action, int times, long gap, Runnable then) {
        if (times <= 0) {
            handler.postDelayed(then, 250);
            return;
        }
        global(action);
        handler.postDelayed(() -> press(action, times - 1, gap, then), gap);
    }

    /** The focused row's value: the inline chooser while focused, else its summary line. */
    private String currentValue() {
        return valueText(focusedRow());
    }

    private static int parseInt(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The lane (signal type: SDR, HDR10, Dolby Vision…) whose mode list holds {@code mode}, in the order
     * Right steps through them. A mode listed in more than one lane (e.g. FILMMAKER MODE) leaves the
     * lane unknown (slow path).
     */
    private List<String> laneOf(String mode) {
        List<String> found = null;
        for (List<String> lane : cfg.lanes) {
            if (!containsLoose(lane, mode)) continue;
            if (found != null) return null;
            found = lane;
        }
        return found;
    }

    private static int indexLoose(List<String> list, String s) {
        for (int i = 0; i < list.size(); i++) if (same(list.get(i), s)) return i;
        return -1;
    }

    /**
     * Fast path: the current mode tells the lane, so press Right exactly as many times as the target
     * is away and check once. Unknown lane or a miss falls back to walking the list.
     */
    private void chooseMode(List<String> targets, Result r, Runnable then) {
        String cur = valueText(focusedRow());
        List<String> lane = cur != null ? laneOf(cur) : null;
        String target = null;
        if (lane != null) for (String t : targets) if (containsLoose(lane, t)) { target = t; break; }
        if (target == null) {
            findMode(targets, null, 0, r, then);
            return;
        }
        int steps = (indexLoose(lane, target) - indexLoose(lane, cur) + lane.size()) % lane.size();
        String goal = target;
        press(GLOBAL_ACTION_DPAD_RIGHT, steps, MODE_KEY_MS, () -> {
            String now = valueText(focusedRow());
            r.mode = now;
            if (same(now, goal)) then.run();
            else walkTo(goal, 0, r, then);
        });
    }

    /**
     * Walk the Picture Mode values with Right, recording every mode this content offers, until back
     * at the start (or on the first choice). Then step to the best available target.
     */
    private void findMode(List<String> targets, String start, int steps, Result r, Runnable then) {
        String cur = valueText(focusedRow());
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
            whenChanged(() -> valueText(focusedRow()), cur, () -> findMode(targets, first, steps + 1, r, then));
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
        String cur = valueText(focusedRow());
        r.mode = cur;
        if (same(cur, target)) {
            then.run();
        } else if (steps >= MAX_MODES) {
            fail(r, "could not reach " + target);
        } else {
            global(GLOBAL_ACTION_DPAD_RIGHT);
            whenChanged(() -> valueText(focusedRow()), cur, () -> walkTo(target, steps + 1, r, then));
        }
    }

    /**
     * OK is the only key that can do damage here (the menu has a Reset row), so re-read the focused
     * row right before pressing it and refuse unless it is exactly the one we mean.
     */
    private boolean pressOkOn(String row) {
        if (!row.equals(titleOf(focusedRow()))) return false;
        global(GLOBAL_ACTION_DPAD_CENTER);
        return true;
    }

    private void closeMenu(int tries, Runnable then) {
        if (menuRoot() == null || tries >= 4) {
            then.run();
            return;
        }
        global(GLOBAL_ACTION_BACK);
        long t0 = SystemClock.uptimeMillis();
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (menuRoot() == null) then.run();
                else if (SystemClock.uptimeMillis() - t0 > SETTLE_MAX_MS) closeMenu(tries + 1, then);
                else handler.postDelayed(this, POLL_MS);
            }
        };
        handler.postDelayed(poll, POLL_MS);
    }

    // ---- node helpers ----------------------------------------------------------------------------

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
            if (root == null || !cfg.pkg.contentEquals(String.valueOf(root.getPackageName()))) continue;
            if (textOf(root, cfg.pageTitleId) != null) return root;
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

    /** A row's title (profiles list the ids rows use; submenu rows may use a vendor id). */
    private String titleOf(AccessibilityNodeInfo node) {
        return firstText(node, cfg.titleIds);
    }

    /** A row's value: the inline chooser while focused, else its summary line. */
    private String valueText(AccessibilityNodeInfo node) {
        return firstText(node, cfg.valueIds);
    }

    private static String firstText(AccessibilityNodeInfo node, List<String> ids) {
        if (node == null) return null;
        for (String id : ids) {
            String t = textOf(node, id);
            if (t != null) return t;
        }
        return null;
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
