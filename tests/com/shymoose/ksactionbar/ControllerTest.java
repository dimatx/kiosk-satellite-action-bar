// SPDX-License-Identifier: Apache-2.0
package com.shymoose.ksactionbar;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/** Drives the real controller with a virtual clock and a fake host, without drawing. */
public final class ControllerTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        followsEntities();
        tapsCallServices();
        holdGuardsLocks();
        failuresAndTimeouts();
        momentaryAndUnavailable();
        fillsLabels();
        autoHideAndPages();
        olderKioskSatellite();
        System.out.println("ControllerTest: " + checks + " checks passed");
    }

    private static void followsEntities() {
        Fixture f = new Fixture(items("light.kitchen", "cover.shade", "light.kitchen"));
        check(f.host.subscribed.equals(set("browser.state", "ha.entity.light.kitchen", "ha.entity.cover.shade")), "follows each entity once");
        check(f.host.barShows == 1, "bar shown once");
        f.plugin.configure(settings(items("cover.shade", "lock.front")));
        f.clock.flush();
        check(f.host.subscribed.equals(set("browser.state", "ha.entity.cover.shade", "ha.entity.lock.front")), "configure follows the new set only");
        check(f.host.unsubscribed.contains("ha.entity.light.kitchen"), "dropped entity unsubscribed");
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("on", "t1", null));
        f.clock.flush();
        check(f.plugin.tiles().size() == 2, "stale events from a dropped entity are ignored");
    }

    private static void tapsCallServices() {
        Fixture f = new Fixture(items("light.kitchen", "cover.shade"));
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("off", "t1", "Kitchen"));
        f.clock.flush();
        Tile before = f.plugin.tiles().get(0);
        check("Kitchen".equals(before.label) && !before.active && !before.pending, "initial tile");
        f.plugin.taps.onTap(0);
        f.clock.flush();
        check(f.host.calls.size() == 1, "tap makes one call");
        Map<String, Object> message = f.host.calls.get(0);
        check("homeassistant".equals(message.get("domain")) && "toggle".equals(message.get("service")), "light toggles");
        check("light.kitchen".equals(((Map<?, ?>) message.get("target")).get("entity_id")), "targets the entity");
        check(f.plugin.tiles().get(0).pending, "tap shows pending at once");
        f.host.replyCall(true, null);
        f.clock.flush();
        check(f.plugin.tiles().get(0).pending, "success alone keeps waiting for the state");
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("on", "t2", "Kitchen"));
        f.clock.flush();
        Tile after = f.plugin.tiles().get(0);
        check(after.active && !after.pending, "state change confirms the tap");
        check(f.host.status.isEmpty(), "no status while working");

        f.plugin.onEvent("ks.ha.entity.cover.shade", stateWith("opening", "t1", "shade", 15));
        f.clock.flush();
        f.plugin.taps.onTap(1);
        check("stop_cover".equals(f.host.calls.get(1).get("service")), "moving shade stops");
    }

    private static void holdGuardsLocks() {
        Fixture f = new Fixture(items("lock.front", "cover.garage"));
        f.plugin.onEvent("ks.ha.entity.lock.front", state("locked", "t1", "Front door"));
        f.plugin.onEvent("ks.ha.entity.cover.garage", stateWith("closed", "t1", "garage", 0));
        f.clock.flush();
        f.plugin.taps.onTap(0);
        f.plugin.taps.onTap(1);
        f.clock.flush();
        check(f.host.calls.isEmpty(), "a tap never unlocks or opens the garage");
        check(f.plugin.taps.onHold(0), "a long press acts");
        check("unlock".equals(f.host.calls.get(0).get("service")), "long press unlocks");
        check(f.plugin.taps.onHold(1), "long press on garage acts");
        check("open_cover".equals(f.host.calls.get(1).get("service")), "long press opens garage");

        Map<String, Object> off = settings(items("lock.front"));
        off.put("holdToConfirm", false);
        Fixture g = new Fixture(off);
        g.plugin.onEvent("ks.ha.entity.lock.front", state("locked", "t1", "Front door"));
        g.clock.flush();
        g.plugin.taps.onTap(0);
        check(g.host.calls.size() == 1, "with the guard off a tap unlocks");
    }

    private static void failuresAndTimeouts() {
        Fixture f = new Fixture(items("light.kitchen"));
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("off", "t1", "Kitchen"));
        f.clock.flush();
        f.plugin.taps.onTap(0);
        f.host.replyCall(false, "unreachable: Home Assistant did not answer");
        f.clock.flush();
        check(!f.plugin.tiles().get(0).pending, "failure ends pending");
        check(f.host.error && f.host.status.contains("Could not switch Kitchen"), "failure is reported");
        check(f.host.calls.size() == 1, "never retried");
        f.plugin.taps.onTap(0);
        f.host.replyCall(true, null);
        f.clock.flush();
        check(f.host.status.isEmpty() && !f.host.error, "a later success clears the error");

        // No state change: the pending look ends on its own.
        f.clock.advance(ActionBarPlugin.PENDING_MS - 1);
        check(f.plugin.tiles().get(0).pending, "still pending before the timeout");
        f.clock.advance(1);
        check(!f.plugin.tiles().get(0).pending, "pending ends after the timeout");

        // A stale timeout from an earlier tap leaves a newer tap pending.
        f.plugin.taps.onTap(0);
        f.clock.advance(5_000);
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("on", "t9", "Kitchen"));
        f.plugin.taps.onTap(0);
        f.clock.advance(5_000);
        check(f.plugin.tiles().get(0).pending, "older timeout does not clear a newer tap");
    }

    private static void momentaryAndUnavailable() {
        Fixture f = new Fixture(items("script.good_night", "light.porch"));
        f.plugin.onEvent("ks.ha.entity.script.good_night", state("off", "t1", "Good night"));
        f.plugin.onEvent("ks.ha.entity.light.porch", state("unavailable", "t1", "Porch"));
        f.clock.flush();
        f.plugin.taps.onTap(0);
        check("turn_on".equals(f.host.calls.get(0).get("service")), "script starts");
        f.host.replyCall(true, null);
        f.clock.flush();
        check(!f.plugin.tiles().get(0).pending, "a script tap ends on success");
        check(f.plugin.tiles().get(1).unavailable, "unavailable entity is marked");
        f.plugin.taps.onTap(1);
        check(!f.plugin.taps.onHold(1), "long press on an unavailable entity does nothing");
        check(f.host.calls.size() == 1, "unavailable entity never calls");

        Map<String, Object> missing = new HashMap<>();
        missing.put("entityId", "light.porch");
        missing.put("status", "missing");
        f.plugin.onEvent("ks.ha.entity.light.porch", missing);
        f.clock.flush();
        check(f.host.status.contains("light.porch was not found"), "missing entity is reported");
    }

    private static void fillsLabels() {
        Fixture f = new Fixture(items("light.kitchen"));
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("off", "t1", "Kitchen"));
        f.clock.advance(ActionBarPlugin.FILL_DELAY_MS);
        check(f.host.saved != null, "labels saved once states arrive");
        List<?> rows = (List<?>) f.host.saved.get("items");
        check("Kitchen".equals(((Map<?, ?>) rows.get(0)).get("label")), "label filled");
        check("mdi:lightbulb-outline".equals(((Map<?, ?>) rows.get(0)).get("icon")), "icon filled");
        f.host.saved = null;
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("on", "t2", "Kitchen"));
        f.clock.advance(ActionBarPlugin.FILL_DELAY_MS);
        check(f.host.saved == null, "nothing saved twice");
    }

    private static void autoHideAndPages() throws Exception {
        Map<String, Object> s = settings(items("light.kitchen"));
        s.put("autoCollapse", 10.0);
        Map<String, Object> page = new HashMap<>();
        page.put("page", "lovelace/settings");
        s.put("hideOn", Arrays.asList(page));
        Fixture f = new Fixture(s);
        f.clock.advance(9_999);
        check(!collapsed(f.plugin), "visible before the delay");
        f.clock.advance(1);
        check(collapsed(f.plugin), "hidden after the delay");
        f.plugin.onEvent("ks.browser.state", new HashMap<String, Object>());
        f.host.reply("getDashboardState", true, map("currentPath", "/lovelace/settings"));
        f.clock.flush();
        check(f.host.hides == 1, "hidden on a listed page");
        check(f.host.status.contains("Hide the bar on"), "status explains why");
        f.plugin.execute("hide", new HashMap<String, Object>());
        f.plugin.stop();
        f.clock.advance(60_000);
        check(f.clock.tasks.isEmpty(), "stop leaves no scheduled work");
    }

    private static void olderKioskSatellite() {
        Fixture f = new Fixture(items("light.kitchen"));
        f.host.noHa = true;
        f.plugin.onEvent("ks.ha.entity.light.kitchen", state("off", "t1", "Kitchen"));
        f.clock.flush();
        f.plugin.taps.onTap(0);
        f.clock.flush();
        check(f.host.status.contains("2026.10.25"), "explains the version needed");
        check(!f.plugin.tiles().get(0).pending, "no pending look without a call");
    }

    private static boolean collapsed(ActionBarPlugin plugin) throws Exception {
        Field field = ActionBarPlugin.class.getDeclaredField("collapsed");
        field.setAccessible(true);
        return (Boolean) field.get(plugin);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static Set<String> set(String... values) { return new LinkedHashSet<>(Arrays.asList(values)); }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private static List<Object> items(String... entities) {
        List<Object> rows = new ArrayList<>();
        for (String entity : entities) rows.add(ActionTest.row(entity, "", "", ""));
        return rows;
    }

    private static Map<String, Object> settings(List<Object> items) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("items", items);
        return settings;
    }

    private static Map<String, Object> state(String value, String updated, String name) {
        return stateWith(value, updated, null, 0, name);
    }

    private static Map<String, Object> stateWith(String value, String updated, String deviceClass, int features) {
        return stateWith(value, updated, deviceClass, features, null);
    }

    private static Map<String, Object> stateWith(String value, String updated, String deviceClass, int features, String name) {
        Map<String, Object> attributes = new HashMap<>();
        if (name != null) attributes.put("friendly_name", name);
        if (deviceClass != null) attributes.put("device_class", deviceClass);
        attributes.put("supported_features", features);
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "available");
        payload.put("state", value);
        payload.put("attributes", attributes);
        payload.put("lastUpdated", updated);
        return payload;
    }

    private static final class Fixture {
        final Clock clock = new Clock();
        final Host host = new Host();
        final ActionBarPlugin plugin = new ActionBarPlugin(clock);

        Fixture(List<Object> items) { this(settings(items)); }

        Fixture(Map<String, Object> settings) {
            plugin.start(host, settings);
            host.reply("getDashboardState", true, map("currentPath", "/lovelace/0"));
            clock.flush();
        }
    }

    private static final class Clock implements ActionBarPlugin.Scheduler {
        private static final class Task {
            final Runnable run;
            final long time;
            Task(Runnable run, long time) { this.run = run; this.time = time; }
        }
        final List<Task> tasks = new ArrayList<>();
        long now;
        public void post(Runnable run) { postDelayed(run, 0); }
        public void postDelayed(Runnable run, long delay) { tasks.add(new Task(run, now + delay)); }
        public void removeCallbacks(Runnable run) { tasks.removeIf(task -> task.run == run); }
        void flush() { advance(0); }
        void advance(long duration) {
            long end = now + duration;
            int count = 0;
            while (!tasks.isEmpty()) {
                tasks.sort(Comparator.comparingLong(task -> task.time));
                Task next = tasks.get(0);
                if (next.time > end) break;
                tasks.remove(0);
                now = next.time;
                next.run.run();
                if (++count > 10_000) throw new AssertionError("runaway scheduler");
            }
            now = end;
        }
    }

    private static final class Host implements PluginHost {
        final Set<String> subscribed = new LinkedHashSet<>();
        final List<String> unsubscribed = new ArrayList<>();
        final Map<String, List<CommandCallback>> replies = new HashMap<>();
        final List<Map<String, Object>> calls = new ArrayList<>();
        final List<CommandCallback> callReplies = new ArrayList<>();
        Map<String, Object> saved;
        int barShows;
        int hides;
        boolean noHa;
        String status = "";
        boolean error;

        public void subscribe(String event) { subscribed.add(event); }
        public void unsubscribe(String event) { subscribed.remove(event); unsubscribed.add(event); }
        public void executeCommand(String name, Map<String, Object> args, CommandCallback callback) {
            replies.computeIfAbsent(name, key -> new ArrayList<>()).add(callback);
        }
        void reply(String name, boolean ok, Object value) { replies.get(name).remove(0).onResult(ok, value, ok ? null : "failed"); }
        public void haCall(Map<String, Object> message, CommandCallback callback) {
            if (noHa) throw new UnsupportedOperationException("Home Assistant access is unavailable");
            calls.add(message);
            callReplies.add(callback);
        }
        void replyCall(boolean ok, String error) { callReplies.remove(0).onResult(ok, null, error); }
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) { barShows++; }
        public void hideOverlay(String key) { hides++; }
        public void setContentInsets(int l, int t, int r, int b) {}
        public void status(String message, boolean error) { this.status = message; this.error = error; }
        public void saveSettings(Map<String, Object> values) { saved = values; }
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) { throw new AssertionError(message); }
    }
}
