// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksactionbar;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * A bar of Home Assistant controls. Each item follows one entity through KS's live entity feed and
 * calls a Home Assistant service through KS's own connection when tapped, so the plugin never holds
 * a token.
 *
 * Threads: plugin callbacks and command results run on the KS plugin worker and take this object's
 * lock. Entity states and pending taps live in concurrent maps, so the main thread reads them
 * without the lock. The main thread owns the overlay and every view, and catches every exception,
 * because one escaping a listener would end the app.
 */
public final class ActionBarPlugin implements KioskPlugin {
    static final String BAR = "bar";
    private static final int BAR_TRANSITION_MS = 180;
    /** How long a tap waits for Home Assistant to report a change before it stops looking pending. */
    static final long PENDING_MS = 10_000;
    /** Labels and icons are saved back once the entities have reported, not after each one. */
    static final long FILL_DELAY_MS = 1500;

    interface Scheduler {
        void post(Runnable task);
        void postDelayed(Runnable task, long delayMs);
        void removeCallbacks(Runnable task);
    }

    private final Scheduler main;
    final ActionViews.Listener taps = new Taps();

    public ActionBarPlugin() {
        this(new Scheduler() {
            private final Handler handler = new Handler(Looper.getMainLooper());
            public void post(Runnable task) { handler.post(task); }
            public void postDelayed(Runnable task, long delayMs) { handler.postDelayed(task, delayMs); }
            public void removeCallbacks(Runnable task) { handler.removeCallbacks(task); }
        });
    }

    ActionBarPlugin(Scheduler main) { this.main = main; }

    private volatile PluginHost host;
    private volatile ActionConfig config = ActionConfig.parse(Collections.<String, Object>emptyMap());
    /** The latest settings, kept so filled-in labels and icons can be saved with everything else. */
    private volatile Map<String, Object> settings = Collections.emptyMap();
    private volatile String path;
    private volatile boolean userHidden;
    private volatile boolean collapsed;
    private final Map<String, EntityState> states = new ConcurrentHashMap<>();
    /** Entities with a tap waiting for Home Assistant, to the tap's token. */
    private final Map<String, Integer> pending = new ConcurrentHashMap<>();
    private final AtomicInteger tapTokens = new AtomicInteger();
    private final AtomicBoolean rebuild = new AtomicBoolean();
    private final AtomicBoolean tilesQueued = new AtomicBoolean();
    private final AtomicReference<ActionViews.Root> root = new AtomicReference<>();
    private final Runnable autoCollapse = this::autoCollapse;
    private final Runnable fill = this::fill;
    // Worker-only state.
    private int readRevision;
    private final Set<String> subscribed = new HashSet<>();
    // Main-thread-only state.
    private boolean shown;
    private int reserved;
    private ActionConfig.Edge reservedEdge;
    private int depth;
    private String lastStatus;
    private boolean lastStatusError;
    private final Map<String, String> errors = new LinkedHashMap<>();

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        // KS may reuse this object for a new session. Nothing from the old one applies.
        path = null;
        userHidden = false;
        readRevision++;
        states.clear();
        pending.clear();
        subscribed.clear();
        root.set(null);
        main.post(this::forgetOverlay);
        config = ActionConfig.parse(Collections.<String, Object>emptyMap());
        this.host = host;
        configure(settings);
        try {
            host.subscribe("browser.state");
        } catch (RuntimeException unsupported) {
            issue(host, "browser", "Could not follow page changes, so Hide the bar on cannot work: " + unsupported.getMessage());
        }
        readDashboard();
    }

    @Override
    public synchronized void configure(Map<String, Object> next) {
        ActionConfig previous = config;
        ActionConfig parsed = ActionConfig.parse(next == null ? Collections.<String, Object>emptyMap() : next);
        settings = next == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<>(next);
        config = parsed;
        if (parsed.collapse != previous.collapse || previous.items.isEmpty()) {
            collapsed = parsed.collapse == ActionConfig.Collapse.START_COLLAPSED;
        }
        if (host == null) return;
        follow(parsed.entities());
        if (!parsed.sameShape(previous)) rebuild.set(true);
        sync();
        pushTiles();
        if (parsed.autoCollapse != previous.autoCollapse) main.post(this::scheduleAutoCollapse);
        report();
        main.removeCallbacks(fill);
        main.postDelayed(fill, FILL_DELAY_MS);
    }

    @Override
    public synchronized void execute(String command, Map<String, Object> arguments) {
        switch (command) {
            case "show": userHidden = false; break;
            case "hide": userHidden = true; break;
            case "toggle": userHidden = !userHidden; break;
            default: throw new IllegalArgumentException("Unknown command: " + command);
        }
        sync();
        report();
    }

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        if ("ks.browser.state".equals(event)) {
            readDashboard();
        } else if (event != null && event.startsWith("ks.ha.entity.")) {
            String entity = event.substring("ks.ha.entity.".length());
            if (!subscribed.contains(entity)) return;
            EntityState next = EntityState.parse(payload);
            EntityState before = states.put(entity, next);
            // A pending tap ends when Home Assistant reports anything new for the entity.
            if (before != null && (!Objects.equals(before.state, next.state) || !Objects.equals(before.lastUpdated, next.lastUpdated))) {
                pending.remove(entity);
            }
            pushTiles();
            report();
            main.removeCallbacks(fill);
            main.postDelayed(fill, FILL_DELAY_MS);
        }
    }

    @Override
    public synchronized void stop() {
        // KS revokes the host, its subscriptions and the overlay before calling stop.
        host = null;
        readRevision++;
        subscribed.clear();
        pending.clear();
        root.set(null);
        main.post(this::forgetOverlay);
    }

    /** Worker. Follows exactly the entities the bar shows. */
    private void follow(Set<String> wanted) {
        PluginHost current = host;
        for (String entity : new ArrayList<>(subscribed)) {
            if (wanted.contains(entity)) continue;
            subscribed.remove(entity);
            states.remove(entity);
            pending.remove(entity);
            try { current.unsubscribe("ha.entity." + entity); } catch (RuntimeException ignored) {}
        }
        for (String entity : wanted) {
            if (subscribed.contains(entity)) continue;
            try {
                current.subscribe("ha.entity." + entity);
                subscribed.add(entity);
                issue(current, "follow:" + entity, null);
            } catch (RuntimeException error) {
                issue(current, "follow:" + entity, "Could not follow " + entity + ": " + error.getMessage());
            }
        }
    }

    /** Ask KS for the current page. Responses can race, so only the latest request is applied. */
    private void readDashboard() {
        PluginHost current = host;
        if (current == null) return;
        final int revision = ++readRevision;
        try {
            current.executeCommand("getDashboardState", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
                synchronized (ActionBarPlugin.this) {
                    if (revision != readRevision || host != current) return;
                    if (!ok || !(data instanceof Map)) {
                        issue(current, "dashboard", "Could not read the dashboard page: " + error);
                        return;
                    }
                    issue(current, "dashboard", null);
                    String next = text(((Map<?, ?>) data).get("currentPath"));
                    if (!Objects.equals(next, path)) {
                        path = next;
                        sync();
                        report();
                    }
                }
            });
        } catch (RuntimeException error) {
            issue(current, "dashboard", "Could not read the dashboard page: " + error.getMessage());
        }
    }

    /** What every item shows right now. Lock-free, so the main thread can call it. */
    List<Tile> tiles() {
        ActionConfig cfg = config;
        List<Tile> tiles = new ArrayList<>(cfg.items.size());
        for (ActionItem item : cfg.items) {
            EntityState state = states.getOrDefault(item.entity, EntityState.UNKNOWN);
            boolean usable = state.usable();
            boolean active = usable && Actions.active(item.entity, state);
            boolean attention = usable && Actions.attention(item.entity, state);
            boolean waiting = pending.containsKey(item.entity);
            String label = item.displayLabel(state);
            String spoken = label + ", " + (usable ? state.state.replace('_', ' ') : "unavailable");
            tiles.add(new Tile(label, item.displayIcon(state, active), active, attention, waiting, !usable, spoken));
        }
        return tiles;
    }

    /** Coalesces state changes into one redraw on the main thread. */
    private void pushTiles() {
        if (!tilesQueued.compareAndSet(false, true)) return;
        main.post(() -> {
            tilesQueued.set(false);
            try {
                ActionViews.Root view = root.get();
                if (view != null) view.setTiles(tiles());
            } catch (RuntimeException error) {
                log(host, "Could not update the bar", error);
            }
        });
    }

    /** Main thread. A tap: acts unless the item needs a long press, in which case it says so. */
    private void tap(int index) {
        ActionConfig cfg = config;
        if (index < 0 || index >= cfg.items.size()) return;
        ActionItem item = cfg.items.get(index);
        EntityState state = states.getOrDefault(item.entity, EntityState.UNKNOWN);
        if (!state.usable()) return;
        if (cfg.holdToConfirm && Actions.needsHold(item.entity, state)) {
            ActionViews.Root view = root.get();
            if (view != null) view.hint(index, Actions.holdHint(item.entity, state));
            return;
        }
        act(item, state);
    }

    /** Main thread. A long press acts on any item, including the ones a tap alone does not move. */
    private boolean hold(int index) {
        ActionConfig cfg = config;
        if (index < 0 || index >= cfg.items.size()) return false;
        ActionItem item = cfg.items.get(index);
        EntityState state = states.getOrDefault(item.entity, EntityState.UNKNOWN);
        if (!state.usable()) return false;
        return act(item, state);
    }

    /**
     * Main thread. Calls the service once. A tap never retries: a call that timed out may still
     * have reached Home Assistant, and a toggle sent twice cancels itself.
     */
    private boolean act(ActionItem item, EntityState state) {
        PluginHost current = host;
        if (current == null) return false;
        Actions.Call call = Actions.call(item.entity, state);
        if (call == null) return false;
        String label = item.displayLabel(state);
        String key = "call:" + item.entity;
        int token = tapTokens.incrementAndGet();
        pending.put(item.entity, token);
        pushTiles();
        main.postDelayed(() -> {
            if (pending.remove(item.entity, token)) pushTiles();
        }, PENDING_MS);
        try {
            current.haCall(call.message(item.entity), (ok, data, error) -> main.post(() -> {
                if (host != current) return;
                if (ok) {
                    errors.remove(key);
                    if (Actions.momentary(item.entity) && pending.remove(item.entity, token)) pushTiles();
                } else {
                    if (pending.remove(item.entity, token)) pushTiles();
                    errors.put(key, "Could not " + call.verb + " " + label + (error == null ? "" : ": " + error));
                }
                reportOnMain();
            }));
            return true;
        } catch (UnsupportedOperationException olderHost) {
            pending.remove(item.entity, token);
            pushTiles();
            errors.put("ha", "Controls need Kiosk Satellite 2026.10.25 or newer.");
            reportOnMain();
            return false;
        } catch (RuntimeException failure) {
            pending.remove(item.entity, token);
            pushTiles();
            errors.put(key, "Could not " + call.verb + " " + label + ": " + failure.getMessage());
            reportOnMain();
            return false;
        }
    }

    /**
     * Main thread. Saves the label and icons the bar picked for items left without them, so the
     * settings page shows what the bar uses. KS does not call configure for the save.
     */
    private void fill() {
        PluginHost current = host;
        if (current == null) return;
        Map<String, Object> values = settings;
        List<Map<String, Object>> filled = ActionItem.filled(values.get("items"), states);
        if (filled == null) return;
        Map<String, Object> next = new LinkedHashMap<>(values);
        next.put("items", filled);
        try {
            current.saveSettings(next);
            settings = next;
        } catch (RuntimeException error) {
            log(current, "Could not save the picked labels and icons", error);
        }
    }

    private void sync() { main.post(this::syncOverlay); }

    /** Main thread. Show, hide or rebuild the overlay. */
    private void syncOverlay() {
        PluginHost current = host;
        if (current == null) return;
        ActionConfig cfg = config;
        boolean visible = !userHidden && cfg.visibleAt(path);
        try {
            if (!visible) {
                main.removeCallbacks(autoCollapse);
                if (shown) {
                    current.hideOverlay(BAR);
                    shown = false;
                }
                reserve(0);
                return;
            }
            // Read the flag first, so a first show also clears a pending rebuild.
            boolean replace = rebuild.getAndSet(false);
            if (!shown || replace) {
                current.showOverlay(BAR, OverlaySpec.at(anchor(cfg)).closeOnBack(false)
                    .enter(OverlaySpec.FADE).exit(OverlaySpec.FADE).transitionDuration(BAR_TRANSITION_MS), new RootFactory());
                errors.remove("overlay");
                if (depth > 0) reserve(depth);
                shown = true;
                scheduleAutoCollapse();
            }
        } catch (UnsupportedOperationException olderHost) {
            errors.put("overlay", "Native overlays need Kiosk Satellite 2026.10.23 or newer.");
            reportOnMain();
        } catch (RuntimeException error) {
            errors.put("overlay", "Could not update the bar: " + error.getMessage());
            reportOnMain();
        }
    }

    /** Main thread. Restarts the countdown that hides an open bar behind its tab. */
    private void scheduleAutoCollapse() {
        main.removeCallbacks(autoCollapse);
        ActionConfig cfg = config;
        if (shown && host != null && !userHidden && cfg.visibleAt(path)
                && !collapsed && cfg.collapse != ActionConfig.Collapse.OFF && cfg.autoCollapse > 0) {
            main.postDelayed(autoCollapse, cfg.autoCollapse * 1000L);
        }
    }

    private void setCollapsed(boolean value) {
        collapsed = value && config.collapse != ActionConfig.Collapse.OFF;
        ActionViews.Root view = root.get();
        if (view != null) view.setCollapsed(collapsed);
        scheduleAutoCollapse();
    }

    private void autoCollapse() {
        try {
            if (shown && !collapsed && host != null) setCollapsed(true);
        } catch (RuntimeException error) {
            log(host, "Could not hide the bar", error);
        }
    }

    /** Main thread. The overlay went with the old session, so only the bookkeeping is reset. */
    private void forgetOverlay() {
        main.removeCallbacks(autoCollapse);
        main.removeCallbacks(fill);
        shown = false;
        depth = 0;
        reserved = 0;
        reservedEdge = null;
        lastStatus = null;
        errors.clear();
    }

    /** Main thread. Keeps the dashboard clear of the bar. Zero gives the space back. */
    private void reserve(int depthDp) {
        PluginHost current = host;
        if (current == null) return;
        ActionConfig cfg = config;
        int amount = cfg.makeRoom ? Math.max(0, depthDp) : 0;
        if (amount == reserved && cfg.edge == reservedEdge) return;
        int left = 0, top = 0, right = 0, bottom = 0;
        switch (cfg.edge) {
            case TOP: top = amount; break;
            case LEFT: left = amount; break;
            case RIGHT: right = amount; break;
            default: bottom = amount; break;
        }
        try {
            current.setContentInsets(left, top, right, bottom);
            reserved = amount;
            reservedEdge = cfg.edge;
        } catch (UnsupportedOperationException | LinkageError olderHost) {
            // Without content insets the bar floats over the dashboard.
        } catch (RuntimeException error) {
            log(current, "Could not make room for the bar", error);
        }
    }

    private static String anchor(ActionConfig cfg) {
        switch (cfg.edge) {
            case TOP: return OverlaySpec.TOP;
            case LEFT: return OverlaySpec.LEFT;
            case BOTTOM: return OverlaySpec.BOTTOM;
            default: return OverlaySpec.RIGHT;
        }
    }

    private void report() { main.post(this::reportOnMain); }

    private void issue(PluginHost current, String key, String message) {
        main.post(() -> {
            if (current == null || host != current) return;
            if (message == null) errors.remove(key);
            else errors.put(key, message);
            reportOnMain();
        });
    }

    /**
     * Main thread. The status line on the plugin page. Empty while everything works, and otherwise
     * says only what needs attention.
     */
    private void reportOnMain() {
        PluginHost current = host;
        if (current == null) return;
        ActionConfig cfg = config;
        StringBuilder text = new StringBuilder();
        boolean error = !cfg.problems.isEmpty() || !errors.isEmpty();
        for (String problem : cfg.problems) text.append(problem).append('\n');
        for (String problem : errors.values()) text.append(problem).append('\n');
        boolean notConfigured = false;
        for (ActionItem item : cfg.items) {
            EntityState state = states.get(item.entity);
            if (state == null) continue;
            if ("missing".equals(state.status)) {
                text.append(item.entity).append(" was not found in Home Assistant.\n");
                error = true;
            } else if ("not_configured".equals(state.status)) {
                notConfigured = true;
            }
        }
        if (notConfigured) {
            text.append("Set up Home Assistant in Kiosk Satellite to use the action bar.\n");
            error = true;
        }
        if (cfg.items.isEmpty()) text.append("Add an item to show the bar.");
        else if (userHidden) text.append("Hidden by the Hide action bar action. Run Show action bar to bring it back.");
        else if (!cfg.visibleAt(path)) text.append("Hidden on this page, which is listed under Hide the bar on.");
        String message = text.toString().trim();
        if (message.length() > 1000) message = message.substring(0, 1000);
        if (message.equals(lastStatus) && error == lastStatusError) return;
        try {
            current.status(message, error);
            lastStatus = message;
            lastStatusError = error;
        } catch (RuntimeException failure) {
            log(current, "Could not report the status", failure);
        }
    }

    private static String text(Object value) { return value instanceof String ? (String) value : null; }

    private static void log(PluginHost current, String what, RuntimeException error) {
        try { if (current != null) current.log(what + ": " + error); } catch (RuntimeException ignored) {}
    }

    /** Touches from the overlay, on the main thread. */
    private final class Taps implements ActionViews.Listener {
        @Override public void onTap(int index) {
            try { tap(index); } catch (RuntimeException error) { log(host, "Tap failed", error); }
        }

        @Override public boolean onHold(int index) {
            try { return hold(index); } catch (RuntimeException error) { log(host, "Long press failed", error); return false; }
        }

        @Override public void onCollapse() {
            try { setCollapsed(true); } catch (RuntimeException error) { log(host, "Could not hide the bar", error); }
        }

        @Override public void onExpand() {
            try { setCollapsed(false); } catch (RuntimeException error) { log(host, "Could not show the bar", error); }
        }

        @Override public void onInteraction() {
            try { scheduleAutoCollapse(); } catch (RuntimeException error) { log(host, "Could not restart auto-hide", error); }
        }

        @Override public void onDepth(int depthDp) {
            try {
                depth = depthDp;
                if (shown) reserve(depthDp);
            } catch (RuntimeException error) { log(host, "Could not make room for the bar", error); }
        }
    }

    private final class RootFactory implements OverlayFactory {
        @Override public View create(Context context, KsTheme theme) {
            ActionViews.Root view = new ActionViews.Root(context, theme, config, tiles(), collapsed, taps);
            root.set(view);
            // States may have changed while the view was being built.
            view.setTiles(tiles());
            return view;
        }

        @Override public void onThemeChanged(View view, KsTheme theme) {
            ((ActionViews.Root) view).restyle(theme);
        }

        @Override public void onDestroy(View view) {
            root.compareAndSet((ActionViews.Root) view, null);
        }
    }
}
